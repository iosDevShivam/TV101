package com.gathin.tvhome;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal ADB client for the TV's own adbd on 127.0.0.1:5555, so the phone remote can do what only the shell
 * may: send keys (through a virtual remote, see TvControl), type text and read the app on screen. The launcher
 * signs in with its own RSA key; the first connection makes the TV ask "Allow network debugging?", and with
 * "Always allow" ticked it never asks again.
 */
final class Adb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** The ASN.1 prefix of a SHA-1 DigestInfo: adbd checks the token's signature as if the token were a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    /** Where a connection attempt got to, for the phone page's status line. */
    enum State { OFF, WAITING_FOR_ALLOW, CONNECTED }

    private final File keyDir;
    private final Object writeLock = new Object();
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile Socket socket;
    private OutputStream out;
    private volatile State state = State.OFF;

    Adb(File keyDir) {
        this.keyDir = keyDir;
    }

    State state() {
        return state;
    }

    boolean connected() {
        return socket != null && !socket.isClosed() && state == State.CONNECTED;
    }

    /** Connects and signs in, waiting up to allowWaitMs for someone to accept the prompt on the TV. */
    synchronized void connect(long allowWaitMs) throws IOException {
        if (connected()) return;
        close();
        KeyPair keys = keys();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        } catch (IOException e) {
            state = State.OFF;
            throw e;
        }
        try {
            signIn(s, keys, allowWaitMs);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            socket = null;
            throw e;
        }
    }

    private void signIn(Socket s, KeyPair keys, long allowWaitMs) throws IOException {
        s.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(s.getInputStream());
        socket = s;
        out = s.getOutputStream();
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean sentKey = false, signed = false;
        s.setSoTimeout(5000);
        while (true) {
            Message m = read(in);
            if (m.command == CNXN) break;
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb message");
            if (!signed) {
                send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                signed = true;
            } else if (!sentKey) {
                // The key is not known yet: send it, and the TV asks whether to allow this computer.
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                sentKey = true;
                state = State.WAITING_FOR_ALLOW;
                s.setSoTimeout((int) allowWaitMs);
            } else {
                throw new IOException("adb key refused");
            }
        }
        s.setSoTimeout(0);
        state = State.CONNECTED;
        Thread reader = new Thread(() -> readLoop(s, in), "adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        if (state == State.CONNECTED) state = State.OFF;
        for (Stream st : streams.values()) st.closed();
        streams.clear();
    }

    /** Runs a shell command and returns what it printed, or throws when the TV could not be reached. */
    String exec(String command, long timeoutMs) throws IOException {
        Stream st = open("exec:" + command);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                st.close();
                break;
            }
            byte[] chunk = st.read(left);
            if (chunk == null) break;
            buf.write(chunk, 0, chunk.length);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Opens a service ("exec:hid -") for a two-way conversation; see Stream. */
    Stream open(String service) throws IOException {
        if (!connected()) throw new IOException("not connected");
        int local = nextId.getAndIncrement();
        Stream st = new Stream(local);
        streams.put(local, st);
        send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        boolean opened;
        try {
            opened = st.ready.tryAcquire(5, TimeUnit.SECONDS) && st.remote != 0;
        } catch (InterruptedException e) {
            opened = false;
        }
        if (!opened) {
            streams.remove(local);
            throw new IOException("adb would not open " + service);
        }
        return st;
    }

    private void readLoop(Socket s, DataInputStream in) {
        try {
            while (true) {
                Message m = read(in);
                Stream st = streams.get(m.arg1);
                if (st == null) {
                    if (m.command == WRTE) send(CLSE, 0, m.arg0, null);
                    continue;
                }
                switch (m.command) {
                    case OKAY:
                        if (st.remote == 0) {
                            st.remote = m.arg0;
                            st.ready.release();
                        } else {
                            st.acked.release();
                        }
                        break;
                    case WRTE:
                        st.incoming.add(m.data);
                        send(OKAY, st.local, st.remote, null);
                        break;
                    case CLSE:
                        streams.remove(st.local);
                        st.closed();
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            synchronized (this) {
                if (socket == s) close();
            }
        }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~command);
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null) throw new IOException("not connected");
            o.write(header.array());
            if (len > 0) o.write(data);
            o.flush();
        }
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad adb message");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private KeyPair keys() throws IOException {
        File priv = new File(keyDir, "adbkey"), pub = new File(keyDir, "adbkey.pub.der");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.isFile() && pub.isFile()) {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Files.write(priv.toPath(), pair.getPrivate().getEncoded());
            Files.write(pub.toPath(), pair.getPublic().getEncoded());
            return pair;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("adb key: " + e.getMessage());
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Signature sig = Signature.getInstance("NONEwithRSA");
            sig.initSign(key);
            sig.update(SHA1_PREFIX);
            sig.update(token);
            return sig.sign();
        } catch (Exception e) {
            throw new IOException("adb sign: " + e.getMessage());
        }
    }

    /** The public key in adbd's own format: Android's mincrypt RSAPublicKey struct in base64, then a name. */
    private static byte[] publicKey(RSAPublicKey key) {
        int words = 64;
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, words * 4));
        b.put(littleEndian(rr, words * 4));
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhome@phone-remote\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v, int size) {
        byte[] big = v.toByteArray();
        byte[] le = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) le[i] = big[big.length - 1 - i];
        return le;
    }

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    /** One open service. write() waits for adbd's OKAY before the next write, as the protocol asks. */
    final class Stream {
        final int local;
        volatile int remote;
        final Semaphore ready = new Semaphore(0);
        final Semaphore acked = new Semaphore(0);
        final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private volatile boolean closed;
        private final byte[] end = new byte[0];

        Stream(int local) {
            this.local = local;
        }

        boolean isOpen() {
            return !closed;
        }

        void write(byte[] data) throws IOException {
            if (closed) throw new IOException("stream closed");
            send(WRTE, local, remote, data);
            try {
                if (!acked.tryAcquire(5, TimeUnit.SECONDS)) throw new IOException("adb write timed out");
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
            if (closed) throw new IOException("stream closed");
        }

        /** The next chunk of output, or null once the service has closed (or nothing came within timeoutMs). */
        byte[] read(long timeoutMs) {
            try {
                byte[] chunk = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
                return chunk == null || chunk == end ? null : chunk;
            } catch (InterruptedException e) {
                return null;
            }
        }

        void close() {
            if (closed) return;
            streams.remove(local);
            try {
                send(CLSE, local, remote, null);
            } catch (IOException ignored) {
            }
            closed();
        }

        void closed() {
            closed = true;
            ready.release();
            acked.release();
            incoming.add(end);
        }
    }
}
