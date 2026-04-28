// filepath: c:\Users\sreem\Desktop\java\BankApiServer.java
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class BankApiServer {
    private static final String DB_URL = "jdbc:sqlite:bank.db";
    private static Connection conn;

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection(DB_URL);
        initDatabase();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", BankApiServer::serveHtml);
        server.createContext("/bank.html", BankApiServer::serveHtml);

        server.createContext("/api/register", BankApiServer::register);
        server.createContext("/api/login", BankApiServer::login);
        server.createContext("/api/account", BankApiServer::account);
        server.createContext("/api/deposit", BankApiServer::deposit);
        server.createContext("/api/withdraw", BankApiServer::withdraw);
        server.createContext("/api/transactions", BankApiServer::transactions);

        server.start();
        System.out.println("http://localhost:8080/index.html");
    }

    static void initDatabase() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS accounts (" +
                    "account_number TEXT PRIMARY KEY," +
                    "name TEXT NOT NULL," +
                    "password TEXT NOT NULL," +
                    "balance REAL NOT NULL DEFAULT 0.0," +
                    "created_at TEXT DEFAULT (datetime('now','localtime')))");
            st.execute("CREATE TABLE IF NOT EXISTS transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "account_number TEXT NOT NULL," +
                    "type TEXT NOT NULL," +
                    "amount REAL NOT NULL," +
                    "balance_after REAL NOT NULL," +
                    "txn_time TEXT DEFAULT (datetime('now','localtime')))");
                   
                    
        }
    }

    static void serveHtml(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (!p.equals("/") && !p.equals("/index.html")) {
            send(ex, 404, "text/plain", "Not Found");
            return;
        }
        Path file = Path.of("index.html");
        if (!Files.exists(file)) {
            send(ex, 404, "text/plain", "index.html missing");
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", Files.readString(file));
    }

    static void register(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex, 405, "{\"success\":false,\"message\":\"Method not allowed\"}"); return; }
        Map<String, String> p = readForm(ex);
        String name = p.getOrDefault("name", "").trim();
        String acc = p.getOrDefault("accountNumber", "").trim();
        String pass = p.getOrDefault("password", "").trim();
        double dep = parseDouble(p.getOrDefault("initialDeposit", "0"));

        if (name.isEmpty() || acc.isEmpty() || pass.isEmpty() || dep < 0) {
            json(ex, 200, "{\"success\":false,\"message\":\"Invalid input\"}");
            return;
        }

        try {
            if (exists(acc)) { json(ex, 200, "{\"success\":false,\"message\":\"Account already exists\"}"); return; }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO accounts(account_number,name,password,balance) VALUES(?,?,?,?)")) {
                ps.setString(1, acc); ps.setString(2, name); ps.setString(3, pass); ps.setDouble(4, dep);
                ps.executeUpdate();
                 
            }
            if (dep > 0) logTxn(acc, "CREDIT (Account Opening)", dep, dep);
            json(ex, 200, "{\"success\":true,\"message\":\"Account created successfully\"}");
          
        } catch (SQLException e) {
            json(ex, 500, "{\"success\":false,\"message\":\"DB error\"}");
        }
    }

    static void login(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex, 405, "{\"success\":false,\"message\":\"Method not allowed\"}"); return; }
        Map<String, String> p = readForm(ex);
        String acc = p.getOrDefault("accountNumber", "");
        String pass = p.getOrDefault("password", "");

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM accounts WHERE account_number=? AND password=?")) {
            ps.setString(1, acc); ps.setString(2, pass);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                json(ex, 200, "{\"success\":true,\"name\":\"" + esc(rs.getString("name")) + "\",\"accountNumber\":\"" + esc(acc) + "\"}");
            } else {
                json(ex, 200, "{\"success\":false,\"message\":\"Invalid credentials\"}");
            }
        } catch (SQLException e) {
            json(ex, 500, "{\"success\":false,\"message\":\"DB error\"}");
        }
    }

    static void account(HttpExchange ex) throws IOException {
        String acc = readQuery(ex).getOrDefault("accountNumber", "");
        try (PreparedStatement ps = conn.prepareStatement("SELECT name,balance FROM accounts WHERE account_number=?")) {
            ps.setString(1, acc);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                json(ex, 200, "{\"success\":true,\"name\":\"" + esc(rs.getString("name")) + "\",\"balance\":" + rs.getDouble("balance") + "}");
            } else {
                json(ex, 200, "{\"success\":false,\"message\":\"Account not found\"}");
            }
        } catch (SQLException e) {
            json(ex, 500, "{\"success\":false,\"message\":\"DB error\"}");
        }
    }

    static void deposit(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex, 405, "{\"success\":false,\"message\":\"Method not allowed\"}"); return; }
        Map<String, String> p = readForm(ex);
        String acc = p.getOrDefault("accountNumber", "");
        double amt = parseDouble(p.getOrDefault("amount", "0"));
        if (amt <= 0) { json(ex, 200, "{\"success\":false,\"message\":\"Amount must be > 0\"}"); return; }

        try {
            double bal = balance(acc);
            double nb = bal + amt;
            updateBalance(acc, nb);
            logTxn(acc, "CREDIT", amt, nb);
            json(ex, 200, "{\"success\":true,\"message\":\"Amount credited\"}");
        } catch (SQLException e) {
            json(ex, 500, "{\"success\":false,\"message\":\"DB error\"}");
        }
    }

    static void withdraw(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex, 405, "{\"success\":false,\"message\":\"Method not allowed\"}"); return; }
        Map<String, String> p = readForm(ex);
        String acc = p.getOrDefault("accountNumber", "");
        double amt = parseDouble(p.getOrDefault("amount", "0"));
        if (amt <= 0) { json(ex, 200, "{\"success\":false,\"message\":\"Amount must be > 0\"}"); return; }

        try {
            double bal = balance(acc);
            if (amt > bal) { json(ex, 200, "{\"success\":false,\"message\":\"Insufficient funds\"}"); return; }
            double nb = bal - amt;
            updateBalance(acc, nb);
            logTxn(acc, "DEBIT", amt, nb);
            json(ex, 200, "{\"success\":true,\"message\":\"Amount debited\"}");
        } catch (SQLException e) {
            json(ex, 500, "{\"success\":false,\"message\":\"DB error\"}");
        }
    }

    static void transactions(HttpExchange ex) throws IOException {
        String acc = readQuery(ex).getOrDefault("accountNumber", "");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,type,amount,balance_after,txn_time FROM transactions WHERE account_number=? ORDER BY id DESC LIMIT 20")) {
            ps.setString(1, acc);
            ResultSet rs = ps.executeQuery();
            StringBuilder sb = new StringBuilder("{\"success\":true,\"transactions\":[");
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(rs.getInt("id"))
                  .append(",\"type\":\"").append(esc(rs.getString("type"))).append("\"")
                  .append(",\"amount\":").append(rs.getDouble("amount"))
                  .append(",\"balanceAfter\":").append(rs.getDouble("balance_after"))
                  .append(",\"time\":\"").append(esc(rs.getString("txn_time"))).append("\"}");
            }
            sb.append("]}");
            json(ex, 200, sb.toString());
        } catch (SQLException e) {
            json(ex, 500, "{\"success\":false,\"message\":\"DB error\"}");
        }
    }

    static boolean exists(String acc) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM accounts WHERE account_number=?")) {
            ps.setString(1, acc);
            return ps.executeQuery().next();
        }
    }

    static double balance(String acc) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM accounts WHERE account_number=?")) {
            ps.setString(1, acc);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getDouble("balance") : 0.0;
        }
    }

    static void updateBalance(String acc, double b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE accounts SET balance=? WHERE account_number=?")) {
            ps.setDouble(1, b); ps.setString(2, acc); ps.executeUpdate();
        }
    }

    static void logTxn(String acc, String type, double amount, double after) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transactions(account_number,type,amount,balance_after) VALUES(?,?,?,?)")) {
            ps.setString(1, acc); ps.setString(2, type); ps.setDouble(3, amount); ps.setDouble(4, after);
            ps.executeUpdate();
        }
    }

    static Map<String, String> readForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parse(body);
    }

    static Map<String, String> readQuery(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return parse(q == null ? "" : q);
    }

    static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isBlank()) return m;
        for (String pair : s.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            m.put(k, v);
        }
        return m;
    }

    static double parseDouble(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return -1; } }
    static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", body);
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
// ...existing code...

server.createContext("/api/change-password", BankApiServer::changePassword);

// ...existing code...

static void changePassword(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
        json(ex, 405, "{\"success\":false,\"message\":\"Method not allowed\"}");
        return;
    }

    Map<String, String> p = readForm(ex);
    String acc = p.getOrDefault("accountNumber", "").trim();
    String oldPass = p.getOrDefault("oldPassword", "").trim();
    String newPass = p.getOrDefault("newPassword", "").trim();

    if (acc.isEmpty() || oldPass.isEmpty() || newPass.isEmpty()) {
        json(ex, 200, "{\"success\":false,\"message\":\"Invalid input\"}");
        return;
    }

    try (PreparedStatement check = conn.prepareStatement(
            "SELECT 1 FROM accounts WHERE account_number=? AND password=?")) {
        check.setString(1, acc);
        check.setString(2, oldPass);
        ResultSet rs = check.executeQuery();

        if (!rs.next()) {
            json(ex, 200, "{\"success\":false,\"message\":\"Current password is incorrect\"}");
            return;
        }
    } catch (SQLException e) {
        json(ex, 500, "{\"success\":false,\"message\":\"DB error\"}");
        return;
    }

    try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE accounts SET password=? WHERE account_number=?")) {
        ps.setString(1, newPass);
        ps.setString(2, acc);
        ps.executeUpdate();
        json(ex, 200, "{\"success\":true,\"message\":\"Password updated successfully\"}");
    } catch (SQLException e) {
        json(ex, 500, "{\"success\":false,\"message\":\"DB error\"}");
    }
}