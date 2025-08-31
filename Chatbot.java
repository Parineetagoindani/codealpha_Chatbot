import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;


 
public class Chatbot {

    /* ---------------------- Knowledge base (FAQ) ---------------------- */
    static class QAPair implements Serializable {
        final String question;
        final String answer;
        final LocalDateTime created;

        QAPair(String q, String a) {
            this.question = q;
            this.answer = a;
            this.created = LocalDateTime.now();
        }

        @Override public String toString() {
            return question + " -> " + answer;
        }
    }

    static class KnowledgeBase implements Serializable {
        private final List<QAPair> faq = new ArrayList<>();

        public void add(String q, String a) {
            faq.add(new QAPair(q, a));
        }
        public List<QAPair> all() { return faq; }

        public boolean isEmpty() { return faq.isEmpty(); }
    }

    /* ---------------------- Simple NLP utilities ---------------------- */

    static class SimpleNLP {
        private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
                "a","an","the","is","are","was","were","am","i","you","he","she","it",
                "and","or","of","to","in","on","for","with","that","this","these","those",
                "what","how","why","when","which","do","does","did","please","me"
        ));

        /** tokenize: lower-case, split on non-word, remove stopwords */
        public static List<String> tokenize(String text) {
            text = text == null ? "" : text.toLowerCase(Locale.ROOT);
            String[] toks = text.split("[^a-z0-9]+");
            List<String> out = new ArrayList<>();
            for (String t : toks) {
                if (t.isEmpty()) continue;
                if (STOPWORDS.contains(t)) continue;
                out.add(t);
            }
            return out;
        }

        /** Build term-frequency vector (map) from token list */
        public static Map<String, Double> tfVector(List<String> tokens) {
            Map<String, Double> tf = new HashMap<>();
            for (String t : tokens) tf.put(t, tf.getOrDefault(t, 0.0) + 1.0);
            // normalize by length (L2) to help cosine later
            double norm = 0.0;
            for (double v : tf.values()) norm += v * v;
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (String k : new ArrayList<>(tf.keySet())) tf.put(k, tf.get(k) / norm);
            }
            return tf;
        }

        /** Cosine similarity between two sparse vectors */
        public static double cosine(Map<String, Double> a, Map<String, Double> b) {
            // iterate smaller map
            Map<String, Double> small = a.size() < b.size() ? a : b;
            Map<String, Double> large = small == a ? b : a;
            double sum = 0.0;
            for (Map.Entry<String, Double> e : small.entrySet()) {
                Double v2 = large.get(e.getKey());
                if (v2 != null) sum += e.getValue() * v2;
            }
            return sum; // since vectors are normalized, this is cosine
        }
    }

    /* ---------------------- Chatbot core ---------------------- */

    static class ChatbotCore {
        private KnowledgeBase kb;
        // cached vectorized questions for quick similarity
        private final List<Map<String, Double>> faqVectors = new ArrayList<>();

        ChatbotCore(KnowledgeBase kb) {
            this.kb = kb;
            rebuildCache();
        }

        public void setKb(KnowledgeBase kb) {
            this.kb = kb;
            rebuildCache();
        }

        public void rebuildCache() {
            faqVectors.clear();
            for (QAPair p : kb.all()) {
                List<String> toks = SimpleNLP.tokenize(p.question);
                faqVectors.add(SimpleNLP.tfVector(toks));
            }
        }

        /** Primary respond method */
        public String respond(String message) {
            String m = message == null ? "" : message.trim();
            if (m.isEmpty()) return "Say something — I'm listening!";

            // quick rule-based greetings
            String low = m.toLowerCase(Locale.ROOT);
            if (low.matches(".*\\b(hi|hello|hey)\\b.*")) return "Hello! How can I help you today?";
            if (low.matches(".*\\b(thanks|thank you|thx)\\b.*")) return "You're welcome! Anything else I can help with?";
            if (low.matches(".*\\b(help|support|assist)\\b.*")) return "I can answer FAQs, take new Q/A pairs, or save/load my memory. Try asking a question.";

            // vectorize incoming message
            List<String> tokens = SimpleNLP.tokenize(m);
            Map<String, Double> v = SimpleNLP.tfVector(tokens);

            // find best matching QA by cosine similarity
            double bestScore = 0.0;
            int bestIdx = -1;
            for (int i = 0; i < faqVectors.size(); i++) {
                double score = SimpleNLP.cosine(v, faqVectors.get(i));
                if (score > bestScore) {
                    bestScore = score; bestIdx = i;
                }
            }

            // thresholds: high confidence -> return answer; medium -> suggest candidate; low -> fallback
            final double HIGH = 0.55;   // tuned for short FAQ (can be lowered with more data)
            final double MED = 0.30;

            if (bestIdx >= 0 && bestScore >= HIGH) {
                return kb.all().get(bestIdx).answer + " (confidence: " + String.format("%.2f", bestScore) + ")";
            } else if (bestIdx >= 0 && bestScore >= MED) {
                return "I think you might mean: \"" + kb.all().get(bestIdx).question + "\"\nAnswer: " + kb.all().get(bestIdx).answer
                        + "\n(If this isn't what you meant, you can teach me by using the Train menu.)\n(conf: " + String.format("%.2f", bestScore) + ")";
            }

            // fallback rule-based patterns for simple requests
            if (low.matches(".*\\b(add|create|train)\\b.*\\bfaq\\b.*") || low.matches(".*\\bteach\\b.*")) {
                return "You can train me using: Menu -> Train. Provide question and answer to add to my knowledge base.";
            }

            // fallback: ask for clarification and offer to train
            return "I'm not sure I understand. You can rephrase, or teach me the correct response via the Train option.";
        }
    }

    /* ---------------------- Persistence helpers ---------------------- */

    static class Persistence {
        static void saveKB(KnowledgeBase kb, String file) throws IOException {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeObject(kb);
            }
        }
        static KnowledgeBase loadKB(String file) throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                return (KnowledgeBase) ois.readObject();
            }
        }
    }

    /* ---------------------- Swing UI ---------------------- */

    private JFrame frame;
    private JTextPane chatPane;
    private JTextField entryField;
    private ChatbotCore bot;
    private KnowledgeBase kb;
    private final String KB_FILE = "chatbot_kb.dat";

    public Chatbot() {
        // load KB if exists, otherwise create default KB
        KnowledgeBase base = null;
        try {
            base = Persistence.loadKB(KB_FILE);
        } catch (Exception ignored) { /* no saved kb */ }
        if (base == null) {
            base = new KnowledgeBase();
            seedDefault(base);
        }
        this.kb = base;
        this.bot = new ChatbotCore(kb);
        createGui();
        printBot("Hello! I'm a simple Java chatbot. Ask me something or use the Menu to train/save/load.");
    }

    private void seedDefault(KnowledgeBase kb) {
        kb.add("what is your name", "I am JavaBot - a demo chatbot.");
        kb.add("how can i save knowledge", "Use the Menu -> Save Knowledge to persist Q/A pairs.");
        kb.add("how to train you", "Menu -> Train allows you to add a question and its answer to my memory.");
        kb.add("what can you do", "I can answer FAQs, be trained with new Q/A, and save/load my memory.");
    }

    private void createGui() {
        frame = new JFrame("AI Chatbot (Java)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(650, 500);
        frame.setLayout(new BorderLayout());

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        JScrollPane scroll = new JScrollPane(chatPane);
        frame.add(scroll, BorderLayout.CENTER);

        entryField = new JTextField();
        frame.add(entryField, BorderLayout.SOUTH);

        JMenuBar menuBar = new JMenuBar();
        JMenu menu = new JMenu("Menu");
        JMenuItem train = new JMenuItem("Train (Add Q/A)");
        JMenuItem viewKB = new JMenuItem("View KB");
        JMenuItem save = new JMenuItem("Save Knowledge");
        JMenuItem load = new JMenuItem("Load Knowledge");
        JMenuItem clear = new JMenuItem("Clear Chat");
        menu.add(train); menu.add(viewKB); menu.addSeparator();
        menu.add(save); menu.add(load); menu.addSeparator();
        menu.add(clear);
        menuBar.add(menu);
        frame.setJMenuBar(menuBar);

        // actions
        entryField.addActionListener(e -> handleUserInput());
        train.addActionListener(e -> trainDialog());
        viewKB.addActionListener(e -> showKB());
        save.addActionListener(e -> saveKB());
        load.addActionListener(e -> loadKB());
        clear.addActionListener(e -> clearChat());

        frame.setVisible(true);
    }

    private void append(String who, String text) {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();
            StyleConstants.setBold(attr, true);
            doc.insertString(doc.getLength(), who + ": ", attr);
            SimpleAttributeSet body = new SimpleAttributeSet();
            StyleConstants.setBold(body, false);
            doc.insertString(doc.getLength(), text + "\n", body);
            chatPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException ex) {
            ex.printStackTrace();
        }
    }

    private void printBot(String s) { append("Bot", s); }
    private void printUser(String s) { append("You", s); }

    private void handleUserInput() {
        String text = entryField.getText().trim();
        if (text.isEmpty()) return;
        printUser(text);
        entryField.setText("");
        // simple typing delay (simulate thinking)
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            String resp = bot.respond(text);
            printBot(resp);
        }).start();
    }

    private void trainDialog() {
        JTextField qf = new JTextField();
        JTextField af = new JTextField();
        Object[] message = {"Question:", qf, "Answer:", af};
        int option = JOptionPane.showConfirmDialog(frame, message, "Train Chatbot", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            String q = qf.getText().trim();
            String a = af.getText().trim();
            if (!q.isEmpty() && !a.isEmpty()) {
                kb.add(q, a);
                bot.rebuildCache();
                printBot("Thanks — I've learned a new Q/A pair.");
            } else {
                JOptionPane.showMessageDialog(frame, "Both question and answer are required.");
            }
        }
    }

    private void showKB() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (QAPair p : kb.all()) {
            sb.append(String.format("%d) Q: %s%n   A: %s%n", i++, p.question, p.answer));
        }
        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(500, 300));
        JOptionPane.showMessageDialog(frame, sp, "Knowledge Base", JOptionPane.PLAIN_MESSAGE);
    }

    private void saveKB() {
        try {
            Persistence.saveKB(kb, KB_FILE);
            printBot("Knowledge saved to disk (" + KB_FILE + ").");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Save failed: " + e.getMessage());
        }
    }

    private void loadKB() {
        try {
            KnowledgeBase loaded = Persistence.loadKB(KB_FILE);
            if (loaded != null) {
                this.kb = loaded;
                this.bot.setKb(kb);
                printBot("Knowledge loaded from disk.");
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Load failed: " + e.getMessage());
        }
    }

    private void clearChat() {
        chatPane.setText("");
    }

    /* ---------------------- main ---------------------- */

    public static void main(String[] args) {
        // Swing must run on EDT
        SwingUtilities.invokeLater(Chatbot::new);
    }
}
