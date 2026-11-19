package writerstudio;

/**
 * Command-line connection test: {@code java -jar WriterStudio.jar --ai-test [API_KEY] [--show-prompt]}.
 * Without a key it uses the one saved by the app. Output is plain English so it reads on any console.
 */
final class AiCli {
    private AiCli() {}

    static int run(String[] args) {
        boolean showPrompt = false;
        String key = null;
        for (int i = 1; i < args.length; i++) {
            if ("--show-prompt".equals(args[i])) showPrompt = true;
            else if (key == null) key = args[i].trim();
        }

        AiService service;
        if (key != null) {
            AiSettings tmp = new AiSettings();          // in memory only: the key is not saved
            tmp.apiKey = key;
            tmp.provider = AiSettings.detect(key);
            service = new AiService(tmp);
            System.out.println("Testing the key given on the command line (it will not be saved).");
        } else {
            service = new AiService();
            System.out.println("Testing the key saved in the app.");
        }

        AiService.Diag d = service.diagnose(System.out::println, new AiClient.Call(), showPrompt, null);
        System.out.println();
        if (d.ok) {
            System.out.println("RESULT: OK - " + d.provider + ", model " + d.model + ", " + d.millis + " ms");
            return 0;
        }
        System.out.println("RESULT: FAILED - " + (d.error != null ? d.error.getMessage() : d.summary));
        System.out.println("Hints: 401/403 = wrong or revoked key; 429 = free limit reached, wait a few minutes;");
        System.out.println("       NETWORK = check your internet/proxy; MODEL_NOT_FOUND = set the model manually in the app (AI > Paste API key > Advanced).");
        return 1;
    }
}
