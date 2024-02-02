package org.pytorch.serve.plugins.endpoint;

// import java.util.Properties;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.pytorch.serve.servingsdk.Context;
import org.pytorch.serve.servingsdk.ModelServerEndpoint;
import org.pytorch.serve.servingsdk.annotations.Endpoint;
import org.pytorch.serve.servingsdk.annotations.helpers.EndpointTypes;
import org.pytorch.serve.servingsdk.http.Request;
import org.pytorch.serve.servingsdk.http.Response;

// import org.pytorch.serve.util.TokenType;

@Endpoint(
        urlPattern = "token",
        endpointType = EndpointTypes.MANAGEMENT,
        description = "Token authentication endpoint")
public class Token extends ModelServerEndpoint {
    private static String apiKey;
    private static String managementKey;
    private static String inferenceKey;
    private static Instant managementExpirationTimeMinutes;
    private static Instant inferenceExpirationTimeMinutes;
    private static Integer timeToExpirationMinutes;
    private SecureRandom secureRandom = new SecureRandom();
    private Base64.Encoder baseEncoder = Base64.getUrlEncoder();

    @Override
    public void doGet(Request req, Response rsp, Context ctx) throws IOException {
        String queryResponse = parseQuery(req);
        String test = "";
        if ("management".equals(queryResponse)) {
            generateKeyFile("management");
        } else if ("inference".equals(queryResponse)) {
            generateKeyFile("inference");
        } else {
            test = "{\n\t\"Error\": " + queryResponse + "\n}\n";
        }
        rsp.getOutputStream().write(test.getBytes(StandardCharsets.UTF_8));
    }

    // parses query and either returns management/inference or a wrong type error
    public String parseQuery(Request req) {
        QueryStringDecoder decoder = new QueryStringDecoder(req.getRequestURI());
        Map<String, List<String>> parameters = decoder.parameters();
        List<String> values = parameters.get("type");
        if (values != null && !values.isEmpty()) {
            if ("management".equals(values.get(0)) || "inference".equals(values.get(0))) {
                return values.get(0);
            } else {
                return "WRONG TYPE";
            }
        }
        return "NO TYPE PROVIDED";
    }

    public String generateKey() {
        byte[] randomBytes = new byte[6];
        secureRandom.nextBytes(randomBytes);
        return baseEncoder.encodeToString(randomBytes);
    }

    public Instant generateTokenExpiration() {
        return Instant.now().plusSeconds(TimeUnit.MINUTES.toSeconds(timeToExpirationMinutes));
    }

    // generates a key file with new keys depending on the parameter provided
    public boolean generateKeyFile(String type) throws IOException {
        String userDirectory = System.getProperty("user.dir") + "/key_file.json";
        File file = new File(userDirectory);
        if (!file.createNewFile() && !file.exists()) {
            return false;
        }
        if (apiKey == null) {
            apiKey = generateKey();
        }
        switch (type) {
            case "management":
                managementKey = generateKey();
                managementExpirationTimeMinutes = generateTokenExpiration();
                break;
            case "inference":
                inferenceKey = generateKey();
                inferenceExpirationTimeMinutes = generateTokenExpiration();
                break;
            default:
                managementKey = generateKey();
                inferenceKey = generateKey();
                inferenceExpirationTimeMinutes = generateTokenExpiration();
                managementExpirationTimeMinutes = generateTokenExpiration();
        }

        JsonArray jsonArray = new JsonArray();
        jsonArray.add(
                "Management Key: "
                        + managementKey
                        + " --- Expiration time: "
                        + managementExpirationTimeMinutes);
        jsonArray.add(
                "Inference Key: "
                        + inferenceKey
                        + " --- Expiration time: "
                        + inferenceExpirationTimeMinutes);
        jsonArray.add("API Key: " + apiKey);

        Files.write(
                Paths.get("key_file.json"),
                new GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(jsonArray)
                        .getBytes(StandardCharsets.UTF_8));

        if (!setFilePermissions()) {
            try {
                Files.delete(Paths.get("key_file.txt"));
            } catch (IOException e) {
                return false;
            }
            return false;
        }
        return true;
    }

    public boolean setFilePermissions() {
        Path path = Paths.get("key_file.json");
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    // checks the token provided in the http with the saved keys depening on parameters
    public boolean checkTokenAuthorization(FullHttpRequest req, String type) {
        String key;
        Instant expiration;
        switch (type) {
            case "token":
                key = apiKey;
                expiration = null;
                break;
            case "management":
                key = managementKey;
                expiration = managementExpirationTimeMinutes;
                break;
            default:
                key = inferenceKey;
                expiration = inferenceExpirationTimeMinutes;
        }

        String tokenBearer = req.headers().get("Authorization");
        if (tokenBearer == null) {
            return false;
        }
        String[] arrOfStr = tokenBearer.split(" ", 2);
        if (arrOfStr.length == 1) {
            return  false;
        }
        String token = arrOfStr[1];

        if (token.equals(key)) {
            if (expiration != null && isTokenExpired(expiration)) {
                return false;
            }
        } else {
            return false;
        }
        return true;
    }

    public boolean isTokenExpired(Instant expirationTime) {
        return !(Instant.now().isBefore(expirationTime));
    }

    public String getManagementKey() {
        return managementKey;
    }

    public String getInferenceKey() {
        return inferenceKey;
    }

    public String getKey() {
        return apiKey;
    }

    public Instant getInferenceExpirationTime() {
        return inferenceExpirationTimeMinutes;
    }

    public Instant getManagementExpirationTime() {
        return managementExpirationTimeMinutes;
    }

    public void setTime(Integer time) {
        timeToExpirationMinutes = time;
    }
}
