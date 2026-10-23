package sev7n.game;

import sev7n.math.Vec3;
import sev7n.scene.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Multiplayer: one game hosts, the others join it (over the network, or on the same PC: two windows
 * of the same game can play together, which is how you test multiplayer on your own).
 *
 * Players share values (where they are, which way they face, their health...) that everyone can read,
 * and send messages ("shoot", "chat", "hit") that everyone else gets. The host passes everything on,
 * so players don't need to know each other. Player 1 is always the host.
 *
 * Everything arrives on background threads and waits in an inbox; the game hands it to scripts at the
 * start of each frame, so scripts never run on two threads at once.
 */
public final class Network {

    public static final int DEFAULT_PORT = 7777;
    private static final double SHARE_RATE = 1 / 30.0; // shared values go out up to 30 times a second

    /** What happened, waiting for the next frame. kind: connected, disconnected, joined, left, message. */
    record Event(String kind, int player, String name, Object value) { }

    private static Network current;

    /** The program's connection (it carries on across scene changes). */
    static Network get() {
        if (current == null) current = new Network();
        return current;
    }

    /** The game stopped: hang up. */
    static void shutdown() {
        if (current != null) current.close();
        current = null;
    }

    private final ConcurrentLinkedQueue<Event> inbox = new ConcurrentLinkedQueue<>();
    private final List<Peer> peers = new CopyOnWriteArrayList<>();  // host: everyone who joined
    private final Map<Integer, Map<String, Object>> shared = new ConcurrentHashMap<>();
    private final Set<Integer> players = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> outgoing = new LinkedHashMap<>(); // shared values not sent yet
    private ServerSocket server;
    private Peer hostLink;                                            // a joined player: the line to the host
    private volatile int me = 0;                                      // 0 = not connected
    private volatile boolean closed;
    private int nextId = 2;
    private double sinceShare;

    Network() { }

    // ---------------------------------------------------------------- connecting

    /** Starts hosting on this port. False if it can't (something already hosts there). */
    synchronized boolean host(int port) {
        if (connected()) return isHost();
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            server = socket;
        } catch (IOException taken) {
            return false;
        }
        closed = false;
        me = 1;
        players.add(1);
        shared.put(1, new ConcurrentHashMap<>());
        Thread accept = new Thread(this::acceptLoop, "Sev7n network host");
        accept.setDaemon(true);
        accept.start();
        inbox.add(new Event("connected", 1, null, null));
        return true;
    }

    /** Joins a game hosted at this address ("localhost" = this PC). False if nobody answers. */
    synchronized boolean join(String address, int port) {
        if (connected()) return !isHost();
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(address, port), 2000);
            socket.setTcpNoDelay(true);
            closed = false;
            hostLink = new Peer(1, socket);
            startReading(hostLink);
            // wait (briefly) for the host's welcome, so playerId() is right straight away
            long until = System.currentTimeMillis() + 2000;
            while (me == 0 && !closed && System.currentTimeMillis() < until) Thread.onSpinWait();
            return me != 0;
        } catch (IOException nobody) {
            return false;
        }
    }

    /** Hosts if nobody does yet, otherwise joins the game on this PC: open the game twice and they find each other. */
    synchronized boolean connect(int port) {
        return host(port) || join("localhost", port);
    }

    boolean connected() {
        return me != 0;
    }

    boolean isHost() {
        return me == 1;
    }

    int playerId() {
        return me;
    }

    /** Everyone playing, including you, in order (1 = the host). */
    List<Object> players() {
        List<Object> out = new ArrayList<>();
        for (int id : new TreeSet<>(players)) out.add((double) id);
        return out;
    }

    synchronized void close() {
        closed = true;
        for (Peer peer : peers) peer.close();
        peers.clear();
        if (hostLink != null) hostLink.close();
        hostLink = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) { }
        server = null;
        boolean was = me != 0;
        me = 0;
        players.clear();
        shared.clear();
        outgoing.clear();
        if (was) inbox.add(new Event("disconnected", 0, null, null));
    }

    // ---------------------------------------------------------------- talking

    /** A message for every other player: onMessage(name, value, from) runs in their scripts. */
    void send(String name, Object value) {
        if (!connected()) return;
        Map<String, Object> message = message("msg");
        message.put("n", name);
        message.put("v", encode(value));
        if (isHost()) broadcast(message, null);
        else hostLink.write(message);
    }

    /** One of your values that everyone can read with shared(yourId, key). */
    synchronized void share(String key, Object value) {
        if (!connected()) return;
        shared.computeIfAbsent(me, k -> new ConcurrentHashMap<>()).put(key, value == null ? NONE : value);
        outgoing.put(key, encode(value));
    }

    /** Another player's (or your own) shared value, or null if they haven't shared it. */
    Object shared(int player, String key) {
        Map<String, Object> values = shared.get(player);
        Object value = values == null ? null : values.get(key);
        return value == NONE ? null : value;
    }

    private static final Object NONE = new Object(); // a shared "none" (maps can't hold null)

    /** Hands what arrived to the game's scripts, then sends shared values that changed. Called once per frame. */
    void deliver(EventSink sink, double dt) {
        for (Event event; (event = inbox.poll()) != null; ) sink.handle(event);
        sinceShare += dt;
        if (sinceShare >= SHARE_RATE) {
            sinceShare = 0;
            flushShares();
        }
    }

    interface EventSink {
        void handle(Event event);
    }

    private synchronized void flushShares() {
        if (!connected() || outgoing.isEmpty()) return;
        Map<String, Object> message = message("share");
        message.put("k", new LinkedHashMap<>(outgoing));
        outgoing.clear();
        if (isHost()) broadcast(message, null);
        else if (hostLink != null) hostLink.write(message);
    }

    private Map<String, Object> message(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", type);
        m.put("from", (double) me);
        return m;
    }

    // ---------------------------------------------------------------- the host

    private void acceptLoop() {
        while (!closed && server != null) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                Peer peer;
                synchronized (this) {
                    peer = new Peer(nextId++, socket);
                    players.add(peer.id);
                    shared.put(peer.id, new ConcurrentHashMap<>());
                    // welcome: your number, who's here, and everything already shared
                    Map<String, Object> welcome = message("welcome");
                    welcome.put("id", (double) peer.id);
                    welcome.put("players", players());
                    Map<String, Object> all = new LinkedHashMap<>();
                    shared.forEach((id, values) -> {
                        Map<String, Object> encoded = new LinkedHashMap<>();
                        values.forEach((k, v) -> encoded.put(k, encode(v == NONE ? null : v)));
                        all.put(String.valueOf(id), encoded);
                    });
                    welcome.put("shared", all);
                    peer.write(welcome);
                    Map<String, Object> joined = message("joined");
                    joined.put("id", (double) peer.id);
                    broadcast(joined, null);
                    peers.add(peer);
                }
                inbox.add(new Event("joined", peer.id, null, null));
                startReading(peer);
            } catch (IOException e) {
                if (!closed) inbox.add(new Event("problem", 0, e.getMessage(), null));
                return;
            }
        }
    }

    private void broadcast(Map<String, Object> message, Peer except) {
        for (Peer peer : peers) if (peer != except) peer.write(message);
    }

    /** What the host does with a line from one of its players. */
    private void hostReceived(Peer from, Map<String, Object> message) {
        message.put("from", (double) from.id); // the host says who it's from (nobody can pretend to be someone else)
        switch (Json.string(message, "t", "")) {
            case "msg" -> {
                inbox.add(new Event("message", from.id, Json.string(message, "n", ""), decode(message.get("v"))));
                broadcast(message, from);
            }
            case "share" -> {
                storeShares(from.id, message.get("k"));
                broadcast(message, from);
            }
            default -> { }
        }
    }

    private void lost(Peer peer) {
        if (closed) return;
        if (peer == hostLink) {
            close(); // the host went away: the game is over
            return;
        }
        if (peers.remove(peer)) {
            players.remove(peer.id);
            shared.remove(peer.id);
            Map<String, Object> left = message("left");
            left.put("id", (double) peer.id);
            broadcast(left, null);
            inbox.add(new Event("left", peer.id, null, null));
        }
    }

    // ---------------------------------------------------------------- a player who joined

    private void playerReceived(Map<String, Object> message) {
        int from = (int) Json.number(message, "from", 0);
        switch (Json.string(message, "t", "")) {
            case "welcome" -> {
                for (Object id : (List<?>) message.getOrDefault("players", List.of())) players.add(((Number) id).intValue());
                if (message.get("shared") instanceof Map<?, ?> all) {
                    all.forEach((id, values) -> storeShares(Integer.parseInt(String.valueOf(id)), values));
                }
                int id = (int) Json.number(message, "id", 0);
                shared.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
                inbox.add(new Event("connected", id, null, null));
                me = id; // last: join() is waiting for this
            }
            case "joined" -> {
                int id = (int) Json.number(message, "id", 0);
                players.add(id);
                inbox.add(new Event("joined", id, null, null));
            }
            case "left" -> {
                int id = (int) Json.number(message, "id", 0);
                players.remove(id);
                shared.remove(id);
                inbox.add(new Event("left", id, null, null));
            }
            case "msg" -> inbox.add(new Event("message", from, Json.string(message, "n", ""), decode(message.get("v"))));
            case "share" -> storeShares(from, message.get("k"));
            default -> { }
        }
    }

    private void storeShares(int player, Object values) {
        if (!(values instanceof Map<?, ?> map)) return;
        Map<String, Object> store = shared.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        map.forEach((k, v) -> {
            Object value = decode(v);
            store.put(String.valueOf(k), value == null ? NONE : value);
        });
    }

    // ---------------------------------------------------------------- the wire

    private void startReading(Peer peer) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(peer.socket.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (!(Json.parse(line) instanceof Map<?, ?> raw)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> message = (Map<String, Object>) raw;
                    if (peer == hostLink) playerReceived(message);
                    else hostReceived(peer, message);
                }
            } catch (IOException | RuntimeException ignored) {
                // the line dropped, or a garbled message: treat it as the player leaving
            }
            lost(peer);
        }, "Sev7n network reader " + peer.id);
        reader.setDaemon(true);
        reader.start();
    }

    /** One connection: a socket and a lock for writing whole lines. */
    private static final class Peer {
        final int id;
        final Socket socket;
        private final PrintWriter out;

        Peer(int id, Socket socket) throws IOException {
            this.id = id;
            this.socket = socket;
            this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        synchronized void write(Map<String, Object> message) {
            out.print(Json.write(message).replace("\n", ""));
            out.print('\n');
            out.flush();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) { }
        }
    }

    /** Seven values to JSON-friendly ones: vectors become {"vec": [x, y, z]}. */
    static Object encode(Object value) {
        return switch (value) {
            case null -> null;
            case Vec3 v -> Map.of("vec", List.of(v.x, v.y, v.z));
            case Number n -> n.doubleValue();
            case String s -> s;
            case Boolean b -> b;
            case List<?> list -> {
                List<Object> out = new ArrayList<>();
                for (Object item : list) out.add(encode(item));
                yield out;
            }
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), encode(v)));
                yield out;
            }
            default -> String.valueOf(value); // things that can't travel (objects, functions) arrive as their name
        };
    }

    static Object decode(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.get("vec") instanceof List<?> xyz && xyz.size() == 3) {
                return new Vec3(((Number) xyz.get(0)).doubleValue(), ((Number) xyz.get(1)).doubleValue(), ((Number) xyz.get(2)).doubleValue());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), decode(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) out.add(decode(item));
            return out;
        }
        return value;
    }
}
