package com.mixtervee.irstudio;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/** Portable IRPLUS PRONTO_HEX import/export; intentionally no Android UI dependencies. */
public final class IrplusCodec {
    private IrplusCodec() {}

    public static final class Item {
        public final String label;
        public final String prontoHex;
        public final int span;
        public final boolean space;
        public Item(String label, String prontoHex, int span, boolean space) {
            this.label = label;
            this.prontoHex = prontoHex;
            this.span = span;
            this.space = space;
        }
    }

    public static final class Remote {
        public final String manufacturer;
        public final String model;
        public final List<Item> items = new ArrayList<>();
        public Remote(String manufacturer, String model) {
            this.manufacturer = manufacturer;
            this.model = model;
        }
        public String displayName() {
            if (manufacturer.isEmpty() || manufacturer.equalsIgnoreCase(model)) return model;
            return manufacturer + " " + model;
        }
    }

    public static final class Signal {
        public final int carrierHz;
        public final int[] microseconds;
        Signal(int carrierHz, int[] microseconds) {
            this.carrierHz = carrierHz;
            this.microseconds = microseconds;
        }
    }

    /** Supported: raw Pronto 0000 (not toggle/compressed/Pronto RC5 formats). */
    public static Signal decodePronto(String code) {
        String[] tokens = code.trim().split("\\s+");
        if (tokens.length < 6 || tokens.length > 10004) {
            throw new IllegalArgumentException("Pronto signal has an invalid number of words.");
        }
        int[] words = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            if (!tokens[i].matches("[0-9A-Fa-f]{1,4}")) {
                throw new IllegalArgumentException("Non-hexadecimal Pronto word.");
            }
            words[i] = Integer.parseInt(tokens[i], 16);
        }
        if (words[0] != 0 || words[1] == 0) {
            throw new IllegalArgumentException("Only raw (0000) Pronto signals are supported.");
        }
        int pairs = words[2] + words[3];
        if (pairs == 0 || words.length != 4 + pairs * 2) {
            throw new IllegalArgumentException("Pronto header and timing count disagree.");
        }
        int frequency = (int) Math.round(1_000_000.0 / (words[1] * 0.241246));
        if (frequency < 10000 || frequency > 100000) {
            throw new IllegalArgumentException("Unsupported IR carrier frequency.");
        }
        int[] durations = new int[pairs * 2];
        long total = 0;
        for (int i = 0; i < durations.length; i++) {
            long micros = Math.round(words[i + 4] * 1_000_000.0 / frequency);
            if (micros < 1 || micros > 1_900_000) {
                throw new IllegalArgumentException("Invalid IR timing duration.");
            }
            total += micros;
            durations[i] = (int) micros;
        }
        // Android ConsumerIrManager supports pulses totalling less than 2 seconds.
        if (total >= 2_000_000) {
            throw new IllegalArgumentException("IR pattern exceeds Android's 2-second limit.");
        }
        return new Signal(frequency, durations);
    }

    public static List<Remote> read(InputStream stream) throws Exception {
        // Android's DocumentBuilderFactory does not implement every JAXP feature.
        // In particular FEATURE_SECURE_PROCESSING and setXIncludeAware(false)
        // can throw before a valid .irplus file is even read. Instead limit input,
        // disallow DTD/entity declarations, and disable external resolution.
        byte[] xml = boundedXml(stream, 2_000_000);
        String text = new String(xml, StandardCharsets.UTF_8);
        if (text.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Only UTF-8 .irplus files are currently supported.");
        }
        if (text.matches("(?is).*<!\\\\s*(DOCTYPE|ENTITY)\\\\b.*")) {
            throw new IllegalArgumentException("XML files with DTD or entity declarations are not allowed.");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setExpandEntityReferences(false);
        // On some Android XML implementations the optional SAX features are
        // unsupported. A bounded input and explicit DOCTYPE rejection above
        // remain in force even if a specific feature cannot be configured.
        optionalFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        optionalFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        optionalFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External XML resources are not allowed.");
        });
        Document doc = builder.parse(new ByteArrayInputStream(xml));
        Element root = doc.getDocumentElement();
        if (root == null || !"irplus".equals(root.getTagName())) {
            throw new IllegalArgumentException("This is not an .irplus XML file.");
        }
        NodeList nodes = root.getChildNodes();
        List<Remote> remotes = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (!"device".equals(el.getTagName())) continue;
            if (!"PRONTO_HEX".equals(el.getAttribute("format"))) continue;
            Remote remote = new Remote(el.getAttribute("manufacturer"),
                    el.getAttribute("model"));
            NodeList children = el.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (!(child instanceof Element)) continue;
                Element b = (Element) child;
                String tag = b.getTagName();
                if ("space".equals(tag)) {
                    int span = parseWidth(b.getAttribute("multiple"), 1);
                    remote.items.add(new Item("", "", span, true));
                } else if ("button".equals(tag)) {
                    String signal = b.getTextContent().trim();
                    if (signal.length() > 40000) {
                        throw new IllegalArgumentException("Pronto command is too long.");
                    }
                    int span = parseWidth(b.getAttribute("span"), 6);
                    // Reject invalid commands before importing the remote.
                    decodePronto(signal);
                    remote.items.add(new Item(
                            b.getAttribute("label"), signal, span, false));
                }
                if (remote.items.size() > 500) {
                    throw new IllegalArgumentException("Too many buttons in one remote.");
                }
            }
            if (!remote.items.isEmpty()) remotes.add(remote);
            if (remotes.size() > 100) {
                throw new IllegalArgumentException("Too many devices in this file.");
            }
        }
        if (remotes.isEmpty()) {
            throw new IllegalArgumentException("No compatible PRONTO_HEX devices found.");
        }
        return remotes;
    }

    private static void optionalFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException | UnsupportedOperationException unsupported) {
            // Some Android parsers don't implement optional JAXP features.
            // The input bound and DTD check still protect the import.
        }
    }

    private static byte[] boundedXml(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = input.read(chunk)) != -1) {
            if (n == 0) continue;
            if (output.size() + n > maxBytes) {
                throw new IllegalArgumentException(".irplus file exceeds the 2 MB import limit.");
            }
            output.write(chunk, 0, n);
        }
        return output.toByteArray();
    }

    private static int parseWidth(String input, int fallback) {
        if (input == null || input.isEmpty()) return fallback;
        int width;
        try { width = Integer.parseInt(input); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid button width."); }
        if (width < 1 || width > 12) throw new IllegalArgumentException("Button width must be 1–12.");
        return width;
    }

    public static String write(Remote remote) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<irplus>\n");
        out.append("  <device manufacturer=\"").append(xml(remote.manufacturer))
                .append("\" model=\"").append(xml(remote.model))
                .append("\" columns=\"12\" format=\"PRONTO_HEX\">\n");
        for (Item item : remote.items) {
            if (item.space) {
                out.append("    <space multiple=\"").append(item.span).append("\" />\n");
            } else {
                out.append("    <button label=\"").append(xml(item.label)).append("\" span=\"")
                        .append(item.span).append("\">")
                        .append(xml(item.prontoHex)).append("</button>\n");
            }
        }
        out.append("  </device>\n</irplus>\n");
        return out.toString();
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
