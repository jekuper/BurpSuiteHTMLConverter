package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.http.message.HttpRequestResponse;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

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
        // Only show if there are messages selected
        if (event.messageEditorRequestResponse().isPresent() || 
            !event.selectedRequestResponses().isEmpty()) {
            
            JMenuItem menuItem = new JMenuItem("Generate HTML Report");
            menuItem.addActionListener(e -> generateReport(event.selectedRequestResponses()));
            
            return List.of(menuItem);
        }
        
        return null;
    }

    private void generateReport(List<HttpRequestResponse> selectedMessages) {
        // Run in background thread to keep Burp UI responsive
        new Thread(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save HTML Report");
            
            // Set default filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddyyyy_HHmmss"));
            chooser.setSelectedFile(new File("burp_report_" + timestamp + ".html"));
            
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
}