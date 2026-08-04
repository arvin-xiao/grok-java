package ai.grok.headless;

import ai.grok.session.api.AgentEventSink;
import ai.grok.session.api.ChatMessage;
import ai.grok.session.api.Session;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Headless (non-TUI) interactive mode using JLine3 for line editing.
 * Provides a simple REPL loop for CLI/CI usage.
 */
public class HeadlessMode {
    private static final Logger log = LoggerFactory.getLogger(HeadlessMode.class);

    private static final String BANNER = """
             ╔═══════════════════════════════════════╗
             ║       Grok Java - AI Coding Agent     ║
             ║          Headless Mode v0.1.0         ║
             ╚═══════════════════════════════════════╝
            """;

    private static final String PROMPT = "grok> ";
    private static final String CONTINUATION_PROMPT = "  ... ";

    private final Session session;
    private final PrintStream out;
    private Terminal terminal;
    private LineReader lineReader;
    private volatile boolean running = true;

    public HeadlessMode(Session session) {
        this(session, System.out);
    }

    public HeadlessMode(Session session, PrintStream out) {
        this.session = session;
        this.out = out;
    }

    /**
     * Start the interactive REPL loop. Blocks until the user exits.
     */
    public void run() {
        try {
            initTerminal();
            printBanner();

            while (running) {
                String input;
                try {
                    input = lineReader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    // Ctrl-C: cancel current turn or exit
                    if (session.state() == Session.SessionState.TURN_RUNNING) {
                        session.cancel();
                        out.println("\n[cancelled]");
                        continue;
                    } else {
                        out.println("\nUse /exit or Ctrl-D to quit.");
                        continue;
                    }
                } catch (EndOfFileException e) {
                    // Ctrl-D: exit
                    break;
                }

                if (input == null || input.isBlank()) {
                    continue;
                }

                String trimmed = input.trim();

                // Handle built-in commands
                if (trimmed.startsWith("/")) {
                    if (!handleCommand(trimmed)) {
                        break;
                    }
                    continue;
                }

                // Send to agent
                processPrompt(trimmed);
            }
        } catch (IOException e) {
            log.error("Terminal initialization failed", e);
            out.println("Error: Failed to initialize terminal - " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    /**
     * Run a single prompt and exit (for non-interactive CLI usage).
     */
    public void runOnce(String prompt) {
        printBanner();
        processPrompt(prompt);
        cleanup();
    }

    private void initTerminal() throws IOException {
        terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(true)
                .jansi(true)
                .build();

        lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .option(LineReader.Option.AUTO_FRESH_LINE, true)
                .build();
    }

    private void printBanner() {
        out.println(BANNER);
        out.println("  Working directory: " + System.getProperty("user.dir"));
        out.println("  Session: " + session.id());
        out.println("  Type /help for commands, /exit to quit.\n");
    }

    private boolean handleCommand(String command) {
        return switch (command.toLowerCase()) {
            case "/exit", "/quit", "/q" -> false;
            case "/help", "/h", "/?" -> {
                printHelp();
                yield true;
            }
            case "/clear" -> {
                session.history(); // just access, don't clear
                out.println("[history not cleared in this version]");
                yield true;
            }
            case "/status" -> {
                out.println("  State: " + session.state());
                out.println("  History: " + session.history().size() + " messages");
                yield true;
            }
            case "/history" -> {
                printHistory();
                yield true;
            }
            default -> {
                out.println("Unknown command: " + command + ". Type /help for available commands.");
                yield true;
            }
        };
    }

    private void printHelp() {
        out.println("""
                
                Available commands:
                  /help, /h       Show this help message
                  /exit, /quit    Exit the session
                  /status         Show session status
                  /history        Show conversation history
                  /clear          Clear screen
                
                Tips:
                  - Press Ctrl-C to cancel a running turn
                  - Press Ctrl-D to exit
                """);
    }

    private void printHistory() {
        var history = session.history();
        out.println("\n  Conversation history (" + history.size() + " messages):");
        for (int i = 0; i < history.size(); i++) {
            var msg = history.get(i);
            String role = switch (msg) {
                case ChatMessage.System s -> "SYSTEM";
                case ChatMessage.User u -> "USER";
                case ChatMessage.Assistant a -> "ASSISTANT";
                case ChatMessage.Tool t -> "TOOL[" + t.toolName() + "]";
            };
            String content = switch (msg) {
                case ChatMessage.System s -> truncate(s.content(), 80);
                case ChatMessage.User u -> truncate(u.content(), 80);
                case ChatMessage.Assistant a -> truncate(a.content(), 80);
                case ChatMessage.Tool t -> truncate(t.result(), 80);
            };
            out.printf("  [%d] %-12s %s%n", i + 1, role, content);
        }
        out.println();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        String singleLine = s.replace("\n", " ").replace("\r", "");
        if (singleLine.length() <= maxLen) return singleLine;
        return singleLine.substring(0, maxLen) + "...";
    }

    private void processPrompt(String input) {
        out.println();

        // Create a streaming sink that prints to console
        AgentEventSink sink = new AgentEventSink() {
            @Override
            public void onTextDelta(String delta) {
                out.print(delta);
                out.flush();
            }

            @Override
            public void onToolCallStart(String callId, String toolName) {
                out.println("\n  [tool] " + toolName + " ...");
            }

            @Override
            public void onToolOutput(String callId, String chunk) {
                // Suppress verbose tool output in headless mode
            }

            @Override
            public void onToolCallEnd(String callId, String result) {
                out.println("  [tool] done.");
            }

            @Override
            public void onTurnComplete() {
                out.println();
            }

            @Override
            public void onError(Throwable error) {
                out.println("\n[error] " + error.getMessage());
            }
        };

        try {
            var result = session.prompt(input, sink).join();
            out.println();
            log.debug("Turn complete: {} turns, {} tokens", result.totalTurns(), result.totalTokens());
        } catch (Exception e) {
            log.error("Prompt failed", e);
            out.println("[error] " + e.getMessage());
        }
    }

    private void cleanup() {
        running = false;
        try {
            session.close();
        } catch (Exception e) {
            log.debug("Session close error", e);
        }
        try {
            if (terminal != null) {
                terminal.close();
            }
        } catch (Exception e) {
            log.debug("Terminal close error", e);
        }
    }
}
