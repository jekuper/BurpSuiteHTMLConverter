package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.http.message.HttpRequestResponse;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class Extension implements BurpExtension, ContextMenuItemsProvider {
    private MontoyaApi api;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("Modern HTML Report Generator");
        api.userInterface().registerContextMenuItemsProvider(this);
        
        api.logging().logToOutput("Extension Loaded.");
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> menuItems = new ArrayList<>();
        
        // Only show if there are messages selected
        if (event.messageEditorRequestResponse().isPresent() || 
            !event.selectedRequestResponses().isEmpty()) {
            
            JMenuItem exportMenuItem = new JMenuItem("Generate HTML Report");
            exportMenuItem.addActionListener(e -> generateReport(event.selectedRequestResponses()));
            menuItems.add(exportMenuItem);
            
            JMenuItem importMenuItem = new JMenuItem("Import from Burp XML");
            importMenuItem.addActionListener(e -> importFromXml());
            menuItems.add(importMenuItem);
        }
        
        return menuItems.isEmpty() ? null : menuItems;
    }

    private void generateReport(List<HttpRequestResponse> selectedMessages) {
        // Run in background thread to keep Burp UI responsive
        new Thread(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save HTML Report");
            
            // Set default filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            chooser.setSelectedFile(new File("burp_report_" + timestamp + ".html"));
            
            FileNameExtensionFilter filter = new FileNameExtensionFilter("HTML Files", "html");
            chooser.setFileFilter(filter);
            
            // Get Burp's main frame as parent
            Frame parentFrame = (Frame) SwingUtilities.getWindowAncestor(
                api.userInterface().swingUtils().suiteFrame()
            );
            
            int returnVal = chooser.showSaveDialog(parentFrame);
            
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                String path = file.getAbsolutePath();
                if (!path.toLowerCase().endsWith(".html")) {
                    path += ".html";
                }
                
                try {
                    processAndWriteReport(path, selectedMessages);
                    api.logging().logToOutput("Report saved to: " + path);
                } catch (Exception ex) {
                    api.logging().logToError("Error generating report: " + ex.getMessage());
                }
            }
        }).start();
    }

    private void importFromXml() {
        // Run in background thread to keep Burp UI responsive
        new Thread(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Burp XML File");
            
            FileNameExtensionFilter filter = new FileNameExtensionFilter("XML Files", "xml");
            chooser.setFileFilter(filter);
            
            Frame parentFrame = (Frame) SwingUtilities.getWindowAncestor(
                api.userInterface().swingUtils().suiteFrame()
            );
            
            int returnVal = chooser.showOpenDialog(parentFrame);
            
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File xmlFile = chooser.getSelectedFile();
                
                try {
                    List<HttpRequestResponse> messages = parseXmlFile(xmlFile);
                    api.logging().logToOutput("Loaded " + messages.size() + " requests from XML");
                    
                    // Now prompt for save location
                    JFileChooser saveChooser = new JFileChooser();
                    saveChooser.setDialogTitle("Save HTML Report");
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                    saveChooser.setSelectedFile(new File("burp_report_" + timestamp + ".html"));
                    FileNameExtensionFilter htmlFilter = new FileNameExtensionFilter("HTML Files", "html");
                    saveChooser.setFileFilter(htmlFilter);
                    
                    int saveReturnVal = saveChooser.showSaveDialog(parentFrame);
                    if (saveReturnVal == JFileChooser.APPROVE_OPTION) {
                        File outFile = saveChooser.getSelectedFile();
                        String path = outFile.getAbsolutePath();
                        if (!path.toLowerCase().endsWith(".html")) {
                            path += ".html";
                        }
                        
                        processAndWriteReportFromXml(path, xmlFile);
                        api.logging().logToOutput("Report saved to: " + path);
                    }
                    
                } catch (Exception ex) {
                    api.logging().logToError("Error importing XML: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        }).start();
    }

    private void processAndWriteReport(String path, List<HttpRequestResponse> messages) throws IOException {
        StringBuilder jsonEntries = new StringBuilder("[");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        DateTimeFormatter genTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (int i = 0; i < messages.size(); i++) {
            HttpRequestResponse message = messages.get(i);
            String timeStr = LocalDateTime.now().format(timeFormatter);

            // Extract data and encode to Base64
            String host = message.httpService() != null ? message.httpService().host() : "";
            String url = message.request().url();
            String method = message.request().method();
            String status = (message.response() != null) ? String.valueOf(message.response().statusCode()) : "N/A";
            
            String reqB64 = Base64.getEncoder().encodeToString(message.request().toByteArray().getBytes());
            String resB64 = (message.response() != null) 
                    ? Base64.getEncoder().encodeToString(message.response().toByteArray().getBytes()) 
                    : "";

            // Manually build JSON to avoid external dependencies
            jsonEntries.append("{");
            jsonEntries.append(String.format("\"id\":%d,", i + 1));
            jsonEntries.append(String.format("\"h\":\"%s\",", toBase64(host)));
            jsonEntries.append(String.format("\"u\":\"%s\",", toBase64(url)));
            jsonEntries.append(String.format("\"m\":\"%s\",", toBase64(method)));
            jsonEntries.append(String.format("\"s\":\"%s\",", toBase64(status)));
            jsonEntries.append(String.format("\"t\":\"%s\",", toBase64(timeStr)));
            jsonEntries.append(String.format("\"q\":\"%s\",", reqB64));
            jsonEntries.append(String.format("\"r\":\"%s\"", resB64));
            jsonEntries.append("}");

            if (i < messages.size() - 1) {
                jsonEntries.append(",");
            }
        }
        jsonEntries.append("]");

        // Load template from resources
        String template = loadResourceAsString("/template.html");
        String finalHtml = template
                .replace("<!-- JSON_DATA -->", jsonEntries.toString())
                .replace("<!-- GEN_TIME -->", LocalDateTime.now().format(genTimeFormatter));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path, StandardCharsets.UTF_8))) {
            writer.write(finalHtml);
        }
    }

    private String toBase64(String input) {
        if (input == null) return "";
        return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private String loadResourceAsString(String fileName) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(fileName)) {
            if (is == null) throw new FileNotFoundException("Template not found in resources");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    private List<HttpRequestResponse> parseXmlFile(File xmlFile) throws Exception {
        // This is a placeholder - XML parsing doesn't create actual HttpRequestResponse objects
        // We'll process the XML directly in processAndWriteReportFromXml
        return new ArrayList<>();
    }

    private void processAndWriteReportFromXml(String path, File xmlFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlFile);
        
        NodeList itemNodes = doc.getElementsByTagName("item");
        
        StringBuilder jsonEntries = new StringBuilder("[");
        DateTimeFormatter genTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (int i = 0; i < itemNodes.getLength(); i++) {
            Element item = (Element) itemNodes.item(i);
            
            String time = getElementText(item, "time");
            String url = getElementText(item, "url");
            String host = getElementText(item, "host");
            String method = getElementText(item, "method");
            String status = getElementText(item, "status");
            
            Element requestElem = (Element) item.getElementsByTagName("request").item(0);
            Element responseElem = (Element) item.getElementsByTagName("response").item(0);
            
            String reqData = "";
            String resData = "";
            
            if (requestElem != null) {
                boolean isBase64 = "true".equals(requestElem.getAttribute("base64"));
                String reqText = requestElem.getTextContent();
                reqData = isBase64 ? reqText : toBase64(reqText);
            }
            
            if (responseElem != null) {
                boolean isBase64 = "true".equals(responseElem.getAttribute("base64"));
                String resText = responseElem.getTextContent();
                resData = isBase64 ? resText : toBase64(resText);
            }

            // Build JSON entry
            jsonEntries.append("{");
            jsonEntries.append(String.format("\"id\":%d,", i + 1));
            jsonEntries.append(String.format("\"h\":\"%s\",", toBase64(host)));
            jsonEntries.append(String.format("\"u\":\"%s\",", toBase64(url)));
            jsonEntries.append(String.format("\"m\":\"%s\",", toBase64(method)));
            jsonEntries.append(String.format("\"s\":\"%s\",", toBase64(status)));
            jsonEntries.append(String.format("\"t\":\"%s\",", toBase64(time)));
            jsonEntries.append(String.format("\"q\":\"%s\",", reqData));
            jsonEntries.append(String.format("\"r\":\"%s\"", resData));
            jsonEntries.append("}");

            if (i < itemNodes.getLength() - 1) {
                jsonEntries.append(",");
            }
        }
        jsonEntries.append("]");

        // Load template and write report
        String template = loadResourceAsString("/template.html");
        String finalHtml = template
                .replace("<!-- JSON_DATA -->", jsonEntries.toString())
                .replace("<!-- GEN_TIME -->", LocalDateTime.now().format(genTimeFormatter));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path, StandardCharsets.UTF_8))) {
            writer.write(finalHtml);
        }
    }

    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return "";
    }
}