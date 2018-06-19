package org.jsii;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsii.api.Callback;
import static org.jsii.JsiiVersion.JSII_RUNTIME_VERSION;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.stream.Collectors;

/**
 * Manages the jsii-runtime child process.
 */
public class JsiiRuntime {
    /**
     * Extract the "+<sha>" postfix from a full version number.
     */
    private static final String VERSION_BUILD_PART_REGEX = "\\+[a-z0-9]+$";

    /**
     * JSON object mapper.
     */
    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * True to print server traces to STDERR.
     */
    private static boolean traceEnabled = false;

    /**
     * The HTTP client connected to this child process.
     */
    private JsiiClient client;

    /**
     * The child procesds.
     */
    private Process childProcess;

    /**
     * Child's standard error.
     */
    private BufferedReader stderr;

    /**
     * Child's standard output.
     */
    private BufferedReader stdout;

    /**
     * Child's standard input.
     */
    private BufferedWriter stdin;

    /**
     * Handler for synchronous callbacks. Must be set using setCallbackHandler.
     */
    private JsiiCallbackHandler callbackHandler;

    /**
     * The main API of this class. Sends a JSON request to jsii-runtime and returns the JSON response.
     * @param request The JSON request
     * @return The JSON response
     * @throws JsiiException If the runtime returns an error response.
     */
    JsonNode requestResponse(final JsonNode request) {
        try {

            // write request
            String str = request.toString();
            this.stdin.write(str + "\n");
            this.stdin.flush();

            // read response
            JsonNode resp = readNextResponse();

            // throw if this is an error response
            if (resp.has("error")) {
                return processErrorResponse(resp);
            }

            // process synchronous callbacks (which 'interrupt' the response flow).
            if (resp.has("callback")) {
                return processCallbackResponse(resp);
            }

            // null "ok" means undefined result (or void).
            return resp.get("ok");

        } catch (IOException e) {
            throw new JsiiException("Unable to send request to jsii-runtime: " + e.toString(), e);
        }
    }

    /**
     * Handles an "error" response by extracting the message and stack trace
     * and throwing a JsiiException.
     * @param resp The response
     * @return Never
     */
    private JsonNode processErrorResponse(final JsonNode resp) {
        String errorMessage = resp.get("error").asText();
        if (resp.has("stack")) {
            errorMessage += "\n" + resp.get("stack").asText();
        }

        throw new JsiiException(errorMessage);
    }

    /**
     * Processes a "callback" response, which is a request to invoke a synchronous callback
     * and send back the result.
     * @param resp The response.
     * @return The next response in the req/res chain.
     */
    private JsonNode processCallbackResponse(final JsonNode resp) {
        if (this.callbackHandler == null) {
            throw new JsiiException("Cannot process callback since callbackHandler was not set");
        }

        Callback callback;
        try {
            callback = OM.treeToValue(resp.get("callback"), Callback.class);
        } catch (JsonProcessingException e) {
            throw new JsiiException(e);
        }

        JsonNode result = null;
        String error = null;
        try {
            result = this.callbackHandler.handleCallback(callback);
        } catch (Exception e) {
            if (e.getCause() instanceof InvocationTargetException) {
                error = e.getCause().getCause().getMessage();
            } else {
                error = e.getMessage();
            }
        }

        ObjectNode completeResponse = JsonNodeFactory.instance.objectNode();
        completeResponse.put("cbid", callback.getCbid());
        if (error != null) {
            completeResponse.put("err", error);
        }
        if (result != null) {
            completeResponse.set("result", result);
        }

        ObjectNode req = JsonNodeFactory.instance.objectNode();
        req.set("complete", completeResponse);

        return requestResponse(req);
    }


    /**
     * Sets the handler for sync callbacks.
     * @param callbackHandler The handler.
     */
    public void setCallbackHandler(final JsiiCallbackHandler callbackHandler) {
        this.callbackHandler = callbackHandler;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();

        if (stderr != null) {
            stderr.close();
        }

        if (stdout != null) {
            stdout.close();
        }

        if (stdin != null) {
            stdin.close();
        }
    }

    /**
     * Starts jsii-server as a child process if it is not already started.
     */
    private void startRuntimeIfNeeded() {
        if (childProcess != null) {
            return;
        }

        // If JSII_DEBUG is set, enable traces.
        String jsiiDebug = System.getenv("JSII_DEBUG");
        if (jsiiDebug != null
                && !jsiiDebug.isEmpty()
                && !jsiiDebug.equalsIgnoreCase("false")
                && !jsiiDebug.equalsIgnoreCase("0")) {
            traceEnabled = true;
        }

        // If JSII_RUNTIME is set, use it to find the jsii-server executable
        // otherwise, we default to "jsii-runtime" from PATH.
        String jsiiRuntimeExecutable = System.getenv("JSII_RUNTIME");
        if (jsiiRuntimeExecutable == null) {
            jsiiRuntimeExecutable = "jsii-runtime";
        }

        if (traceEnabled) {
            System.err.println("jsii-runtime: " + jsiiRuntimeExecutable);
        }

        ProcessBuilder pb = new ProcessBuilder(jsiiRuntimeExecutable);

        if (traceEnabled) {
            pb.environment().put("JSII_DEBUG", "1");
        }

        try {
            this.childProcess = pb.start();
        } catch (IOException e) {
            throw new JsiiException("Cannot find the 'jsii-runtime' executable (JSII_RUNTIME or PATH)");
        }

        try {
            OutputStreamWriter stdinStream = new OutputStreamWriter(this.childProcess.getOutputStream(), "UTF-8");
            InputStreamReader stdoutStream = new InputStreamReader(this.childProcess.getInputStream(), "UTF-8");
            InputStreamReader stderrStream = new InputStreamReader(this.childProcess.getErrorStream(), "UTF-8");

            this.stderr = new BufferedReader(stderrStream);
            this.stdout = new BufferedReader(stdoutStream);
            this.stdin = new BufferedWriter(stdinStream);

            handshake();

            this.client = new JsiiClient(this);

            // if trace is enabled, start a thread that continuously reads from the child process's
            // STDERR and prints to my STDERR.
            if (traceEnabled) {
                startPipeErrorStreamThread();
            }

            // if child exits, we can't recover from that because we effectively lost all state.
            startProcessMonitorThread();

        } catch (IOException e) {
            throw new JsiiException(e);
        }
    }

    /**
     * Verifies the "hello" message and runtime version compatibility.
     * In the meantime, we require full version compatibility, but we should use semver eventually.
     */
    private void handshake() {
        JsonNode helloResponse = this.readNextResponse();

        if (!helloResponse.has("hello")) {
            throw new JsiiException("Expecting 'hello' message from jsii-runtime");
        }

        String runtimeVersion = helloResponse.get("hello").asText();
        assertVersionCompatibleWith(runtimeVersion);
    }

    /**
     * Reads the next response from STDOUT of the child process.
     * @return The parsed JSON response.
     * @throws JsiiException if we couldn't parse the response.
     */
    JsonNode readNextResponse() {
        try {
            String responseLine = this.stdout.readLine();
            if (responseLine == null) {
                String error = this.stderr.lines().collect(Collectors.joining("\n\t"));
                throw new JsiiException("Child process exited unexpectedly: " + error);
            }
            return OM.readTree(responseLine);
        } catch (IOException e) {
            throw new JsiiException("Unable to read reply from jsii-runtime: " + e.toString(), e);
        }
    }

    /**
     * Starts a thread that monitors the child process. If the process exits, we are doomed, so just throw
     * a big exception.
     */
    private void startProcessMonitorThread() {
        Thread daemon = new Thread(() -> {
            int exitCode = -1;
            try {
                exitCode = this.childProcess.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            throw new JsiiException("jsii-runtime exited unexpectedly with exit code " + exitCode);
        });

        daemon.setDaemon(true);
        daemon.start();
    }

    /**
     * Starts a thread that pipes STDERR from the child process to our STDERR.
     */
    private void startPipeErrorStreamThread() {
        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    String line = stderr.readLine();
                    System.err.println(line);
                    if (line == null) {
                        break;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        daemon.setDaemon(true);
        daemon.start();
    }

    /**
     * This will return the server process in case it is not already started.
     * @return A {@link JsiiClient} connected to the server process.
     */
    public JsiiClient getClient() {
        this.startRuntimeIfNeeded();
        if (this.client == null) {
            throw new JsiiException("Client not created");
        }
        return this.client;
    }

    /**
     * Prints jsii-server traces to STDERR.
     */
    public static void enableTrace() {
        traceEnabled = true;
    }

    /**
     * Asserts that a peer runtimeVersion is compatible with this Java runtime version, which means
     * they share the same version components, with the possible exception of the build number.
     *
     * @param runtimeVersion the peer runtime's version, possibly including build number.
     *
     * @throws JsiiException if {@code runtimeVersion} and {@link RUNTIME_VERSION} aren't equal.
     */
    static void assertVersionCompatibleWith(final String runtimeVersion) {
        final String shortActualVersion = runtimeVersion.replaceAll(VERSION_BUILD_PART_REGEX, "");
        final String shortExpectedVersion = JSII_RUNTIME_VERSION.replaceAll(VERSION_BUILD_PART_REGEX, "");
        if (shortExpectedVersion.compareTo(shortActualVersion) != 0) {
            throw new JsiiException("Incompatible jsii-runtime version. Expecting "
                    + shortExpectedVersion
                    + ", actual was " + shortActualVersion);
        }
    }
}