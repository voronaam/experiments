package dev.codecity.github;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * An in-house stand-in for the GitHub API on the loopback interface, for the pull-request tests: every request is
 * recorded ({@link #requests}) and answered by a function of the test ({@link Request} → {@link Reply}). Built on the
 * JDK's own HTTP server, so it needs no dependency. The Rust ({@code github_tests.rs}) and D ({@code github_tests.d})
 * editions have the same fake, and their tests the same scenarios.
 */
final class FakeGitHub implements AutoCloseable {

    /** One request as the server saw it: {@code target} is the path with the query string. */
    record Request(String method, String target, String authorization, String body) {
    }

    /** An answer: status, extra headers and body. */
    record Reply(int status, Map<String, String> headers, byte[] body) {

        static Reply json(String body) {
            return new Reply(200, Map.of(), body.getBytes(StandardCharsets.UTF_8));
        }

        static Reply json(String body, Map<String, String> headers) {
            return new Reply(200, headers, body.getBytes(StandardCharsets.UTF_8));
        }

        static Reply status(int status, Map<String, String> headers) {
            return new Reply(status, headers, "{\"message\":\"no\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();

    /** Starts serving on 127.0.0.1 at a free port. */
    FakeGitHub(Function<Request, Reply> answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            synchronized (requests) {
                requests.add(request);
            }
            Reply reply;
            try {
                reply = answer.apply(request);
            } catch (RuntimeException e) {
                reply = new Reply(500, Map.of(), String.valueOf(e).getBytes(StandardCharsets.UTF_8));
            }
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply.body());
            } catch (IOException e) {
                // the client stopped reading (the response size cap): nothing to do
            }
        });
        server.start();
    }

    /** {@code http://127.0.0.1:<port>}. */
    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * The environment of a fetch against this server: its REST base ({@code /api/v3}) and GraphQL endpoint
     * ({@code /api/graphql}), the variables {@code vars} and programs found on the {@code PATH} of {@code vars} only.
     */
    PullRequests.Environment environment(Map<String, String> vars) {
        Function<String, Optional<Path>> programs = name -> Git.lookup(name, vars::get);
        return new PullRequests.Environment(vars::get, programs, base() + "/api/v3", base() + "/api/graphql");
    }

    /** The requests received so far, in order. */
    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
