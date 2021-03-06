import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.bind.DatatypeConverter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * Copyright 2015 TIBCO Software Inc.
 * All rights reserved.
 *
 * This software is confidential and proprietary information of TIBCO Software Inc.
 *
 */

/**
 * @author <a href="mailto:vnalawad@tibco.com">Vijay Nalawade</a>
 *
 * @since 1.0.0
 */
public class ProfileTokenResolver {

    static String  TOKEN_DELIMITER  = "#";
    static String  pattern          = "\\" + TOKEN_DELIMITER + "([^" + TOKEN_DELIMITER + "]+)\\" + TOKEN_DELIMITER;
    static String  PROFILE_ROOT_DIR = System.getenv("HOME") + "/tmp";
    static boolean isDebugOn        = System.getenv("BW_LOGLEVEL") != null && System.getenv("BW_LOGLEVEL").equalsIgnoreCase("debug");

    public static void main(String[] args) throws Throwable {

        String disableSsl = System.getenv("DISABLE_SSL_VERIFICATION");
        if (Boolean.parseBoolean(disableSsl)) {
            disable_ssl_verification();
        }

        Map<String, Value> tokenMap = new HashMap<String, Value>();
        try {
            tokenMap.put("BWCE_APP_NAME", new Value(System.getProperty("BWCE_APP_NAME"), Type.SYSPROP));
            collectEnvVariables(tokenMap);
            collectPropertiesFromConsul(tokenMap);
            resolveTokens(tokenMap);
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        } finally {
            tokenMap.clear();
        }
    }

    /**
     * This method collects value of ENV variables
     * 
     * @param valueMap
     */
    private static void collectEnvVariables(Map<String, Value> valueMap) {
        Iterator<String> sysPropsItr = System.getenv().keySet().iterator();
        while (sysPropsItr.hasNext()) {
            String varName = sysPropsItr.next();
            valueMap.put(varName, new Value(System.getenv().get(varName), Type.ENV));
        }
    }

    /**
     * 
     */

    private static void collectPropertiesFromConsul(Map<String, Value> valueMap) throws Exception {
        String profileName = System.getenv("APP_CONFIG_PROFILE");
        String appName = valueMap.get("BWCE_APP_NAME").value;
        if (profileName == null || appName == null) {
            return;
        }

        String consulServerUri = getConsulAgentURI();
        if (consulServerUri != null) {

            ObjectMapper mapper = new ObjectMapper();
            URL endpointURL = new URL(consulServerUri + "kv/" + appName + "/" + profileName + "?recurse");
            if (isDebugOn) {
                System.out.println("Loading properties from Consul [" + endpointURL.toString() + "]");
            }
            try {
                JsonNode response = mapper.readTree(endpointURL);
                if (response.isArray()) {
                    for (int i = 0; i < response.size(); i++) {
                        JsonNode propNode = response.get(i);
                        String propName = propNode.get("Key").asText();
                        if (!profileName.isEmpty()) {
                            propName = propName.replace(appName + "/" + profileName + "/", "");
                        }
                        if (!propName.isEmpty()) {
                            String propValue = propNode.get("Value").asText();
                            valueMap.put(propName, new Value(new String(DatatypeConverter.parseBase64Binary(propValue)), Type.APPCONFIG));
                        }
                    }
                }
            } catch (Throwable e) {
                throw new Exception("Failed to load properties from URL [" + endpointURL.toString()
                        + "]. Check Key/Value store configuration for the Application[" + appName + "]", e);
            }
        }
    }

    private static String getConsulAgentURI() {
        String consulServerUri = System.getenv("CONSUL_SERVER_URL");
        if (consulServerUri != null && !consulServerUri.isEmpty()) {
            try {
                URL url = new URL(consulServerUri);
                String hostName = url.getHost();
                if (!hostName.contains(".") && isK8s()) {
                    String consulServiceName = hostName.replace("-", "_").toUpperCase();
                    String consulUri = System.getenv(consulServiceName + "_PORT");
                    consulServerUri = consulUri.replace("tcp", "http");
                }
            } catch (MalformedURLException e) {
                return null;
            }

            if (!consulServerUri.endsWith("/")) {
                consulServerUri = consulServerUri + "/";
            }
            int size = consulServerUri.split("v[0-9]+").length;
            if (size == 1) {
                consulServerUri = consulServerUri + "v1/";
            }
        }
        return consulServerUri;
    }

    private static boolean isK8s() {
        return System.getenv("KUBERNETES_SERVICE_HOST") != null;
    }
    private static void resolveTokens(Map<String, Value> tokenMap) throws Exception {

        if (isDebugOn) {
            System.out.println("Substituting Profile values for BWCE Application [" + tokenMap.get("BWCE_APP_NAME").value + "]");
        }

        Path source = Paths.get(PROFILE_ROOT_DIR, "pcf.substvar");

        if (Files.isSymbolicLink(source)) {
            source = Files.readSymbolicLink(source);
        }

        List<String> contents = new ArrayList<>();
        File originalFile = source.toFile();
        // Construct the new file that will later be renamed to the original
        // filename.
        Path target = Paths.get(PROFILE_ROOT_DIR, "pcf_updated.substvar");
        File tempFile = target.toFile();

        try (PrintWriter writer = new PrintWriter(tempFile, StandardCharsets.UTF_8.toString());
                BufferedReader br = new BufferedReader(new FileReader(originalFile))) {

            String line;
            String appPropName = null;
            while ((line = br.readLine()) != null) {
                if (line.contains("<name>")) {
                    appPropName = line.trim().replace("<name>", "").replace("</name>", "");
                }
                while (line.contains(TOKEN_DELIMITER)) {
                    String oldLine = line;
                    Pattern p = Pattern.compile(pattern);
                    Matcher m = p.matcher(line);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        String var = m.group(1);
                        Value val = tokenMap.get(var);
                        if (val == null) {
                            throw new Exception("Value not found for Token [" + var + "]. Ensure environment variable is set.");
                        }
                        m.appendReplacement(sb, "");
                        sb.append(val.value);
                        if (m.groupCount() == 1 && val.type == Type.APPCONFIG) {
                            contents.add(var + "=" + appPropName);
                        }
                    }
                    m.appendTail(sb);
                    line = sb.toString();

                    if (line.equals(oldLine)) {
                        break;
                    }
                }

                // Replace lookupValue tag with value tag
                if (line.contains("lookupValue")) {
                    line = line.replace("lookupValue", "value");
                }

                writer.println(line);
            }
            writer.flush();
        }

        if (!contents.isEmpty()) {
            try {
                Path configPropsFile = Paths.get(System.getenv("HOME"), "keys.properties");
                Files.write(configPropsFile, contents, StandardCharsets.UTF_8);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Delete the original file
        if (!originalFile.delete()) {
            System.out.println("Could not delete file");
            return;
        }

        // Rename the new file to the filename the original file had.
        if (!tempFile.renameTo(originalFile)) {
            System.out.println("Could not rename file");
        }

    }

    private static void disable_ssl_verification() {

        try {

            // Create a trust manager that does not validate certificate chains
            TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            } };

            // Install the all-trusting trust manager
            SSLContext sc;
            sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Create all-trusting host name verifier
            HostnameVerifier allHostsValid = new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };

            // Install the all-trusting host verifier
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

        } catch (NoSuchAlgorithmException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (KeyManagementException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    static class Value {
        String value;
        Type   type;

        public Value(String val, Type type) {
            this.value = val;
            this.type = type;
        }
    }

    enum Type {
        ENV, APPCONFIG, SYSPROP
    }
}
