package ai.grok.app;

/**
 * CLI argument parser. Handles command-line options before Spring Boot starts.
 */
public record CliArgs(
        String workingDirectory,
        boolean headless,
        String prompt,
        boolean showHelp,
        boolean showVersion
) {
    public static CliArgs parse(String[] args) {
        String workDir = System.getProperty("user.dir");
        boolean headless = true;
        String prompt = null;
        boolean help = false;
        boolean version = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> help = true;
                case "-v", "--version" -> version = true;
                case "--headless" -> headless = true;
                case "--interactive" -> headless = false;
                case "-d", "--directory" -> {
                    if (i + 1 < args.length) {
                        workDir = args[++i];
                    }
                }
                case "-p", "--prompt" -> {
                    if (i + 1 < args.length) {
                        prompt = args[++i];
                    }
                }
                default -> {
                    // Treat first non-flag argument as a prompt
                    if (prompt == null && !args[i].startsWith("-")) {
                        prompt = args[i];
                    }
                }
            }
        }

        return new CliArgs(workDir, headless, prompt, help, version);
    }

    public static void printHelp() {
        System.out.println("""
                grok-java - AI Coding Agent
                
                Usage: grok [options] [prompt]
                
                Options:
                  -h, --help              Show this help message
                  -v, --version           Show version
                  -d, --directory <dir>   Set working directory (default: current directory)
                  -p, --prompt <text>     Run a single prompt and exit
                  --headless              Use headless mode (default)
                  --interactive           Use interactive mode
                
                Environment Variables:
                  OPENAI_API_KEY          OpenAI API key
                  OPENAI_BASE_URL         Custom OpenAI API base URL
                  GROK_MODEL              Model name override
                  GROK_HOME               Configuration directory (default: ~/.grok)
                
                Examples:
                  grok                     Start interactive session
                  grok -p "fix the bug"   Run a single prompt
                  grok -d /path/to/project  Set working directory
                """);
    }
}
