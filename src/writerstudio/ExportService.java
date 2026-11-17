package writerstudio;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.awt.Font;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;

/** Exports the selected Binder nodes while preserving their tree order. */
final class ExportService {
    private ExportService() {}

    static void exportDocx(File out, Project project, Set<Item> selected) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream stream = new FileOutputStream(out)) {
            for (Item item : project.draft().items()) writeDocx(doc, item, selected, 0);
            doc.write(stream);
        }
    }

    private static void writeDocx(XWPFDocument doc, Item item, Set<Item> selected, int depth) {
        boolean included = selected.contains(item);
        if (included) {
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle(depth == 0 ? "Heading1" : "Heading" + Math.min(depth + 1, 6));
            XWPFRun headingRun = heading.createRun();
            headingRun.setText(item.getTitle());
            appendDocxContent(doc, item.getContent());
        }
        for (Item child : item.items()) writeDocx(doc, child, selected, depth + (included ? 1 : 0));
    }

    private static void appendDocxContent(XWPFDocument doc, String content) {
        String[] paragraphs = content.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n", -1);
        for (String text : paragraphs) {
            XWPFParagraph paragraph = doc.createParagraph();
            paragraph.setAlignment(ParagraphAlignment.LEFT);
            paragraph.setFirstLineIndent(360);
            paragraph.setSpacingAfter(0);
            paragraph.setSpacingBetween(1.15);
            XWPFRun run = paragraph.createRun();
            run.setFontFamily("Serif");
            run.setFontSize(12);
            run.setText(text.replace("\n", " ").trim());
        }
    }

    static void exportPdf(File out, Project project, Set<Item> selected) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = loadFont(doc);
            PdfWriter writer = new PdfWriter(doc, font);
            for (Item item : project.draft().items()) writer.write(item, selected, 0);
            writer.closePage();
            doc.save(out);
        }
    }

    private static PDFont loadFont(PDDocument doc) throws IOException {
        String windows = System.getenv("WINDIR");
        File[] candidates = {
                windows == null ? null : new File(windows, "Fonts\\arial.ttf"),
                windows == null ? null : new File(windows, "Fonts\\segoeui.ttf")
        };
        for (File candidate : candidates) {
            if (candidate != null && candidate.exists()) return PDType0Font.load(doc, candidate);
        }
        return PDType1Font.HELVETICA;
    }

    private static final class PdfWriter {
        private final PDDocument document;
        private final PDFont font;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;

        PdfWriter(PDDocument document, PDFont font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        void write(Item item, Set<Item> selected, int depth) throws IOException {
            boolean included = selected.contains(item);
            if (included) {
                writeHeading(item.getTitle(), depth);
                writeContent(item.getContent());
            }
            for (Item child : item.items()) write(child, selected, depth + (included ? 1 : 0));
        }

        void writeHeading(String text, int depth) throws IOException {
            ensureSpace(34);
            float size = Math.max(12, 18 - Math.min(depth, 4));
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(54, y);
            stream.showText(text);
            stream.endText();
            y -= size + 10;
        }

        void writeContent(String content) throws IOException {
            String[] paragraphs = content.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n", -1);
            for (String paragraph : paragraphs) {
                String text = paragraph.trim();
                if (text.isEmpty()) {
                    y -= 10;
                    continue;
                }
                for (String line : wrap(text, 12, 450)) {
                    ensureSpace(18);
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(72, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 16;
                }
                y -= 4;
            }
        }

        void closePage() throws IOException {
            if (stream != null) stream.close();
        }

        private void newPage() throws IOException {
            page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - 54;
        }

        private void ensureSpace(float height) throws IOException {
            if (y - height < 54) {
                stream.close();
                newPage();
            }
        }

        private String[] wrap(String text, float size, float width) throws IOException {
            java.util.List<String> lines = new java.util.ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split("\\s+")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000f * size > width && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            if (line.length() > 0) lines.add(line.toString());
            return lines.toArray(new String[0]);
        }
    }
}
