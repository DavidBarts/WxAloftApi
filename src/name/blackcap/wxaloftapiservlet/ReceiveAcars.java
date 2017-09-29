package name.blackcap.wxaloftapiservlet;

import java.io.*;
import java.nio.charset.Charset;
import javax.json.*;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Responsible for receiving demodulated ACARS packets from remote receivers.
 * 
 * @author David Barts <n5jrn@me.com>
 */
public class ReceiveAcars extends HttpServlet {
    private static final long serialVersionUID = -8389925342660744959L;
    Charset UTF8 = Charset.forName("UTF-8");
    
    /**
     * Process a POST request by receiving ACARS data.
     * @param req     HttpServletRequest
     * @param resp    HttpServletResponse
     */
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Take offense at unsupported content types.
        String cType = req.getContentType();
        if (cType == null || !cType.equals("application/json")) {
            resp.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
            return;
        }
        
        // Read the request body.
        JsonStructure js = null;
        try (JsonReader reader = Json.createReader(req.getReader())) {
            // Take offense at garbage JSON.
            try {
                js = reader.read();
            } catch (JsonException e) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (invalid JSON)");
                return;
            }
        }
        
        // We must get a JsonObject
        if (!(js instanceof JsonObject)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (expecting JSON object)");
            return;
        }
        
        // Obtain fields, taking offense if any are missing
        JsonObject obj = (JsonObject) js;
        String auth = obj.getString("auth");
        if (auth == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing auth)");
            return;
        }
        String time = obj.getString("time");
        if (time == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing time)");
            return;
        }
        JsonNumber channel = obj.getJsonNumber("channel");
        if (channel == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing channel)");
            return;
        }
        String message = obj.getString("message");
        if (message == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing message)");
            return;
        }
        
        // The authenticator will be validated here. On failure, return a 403
        // (Forbidden) error. 401 (Unauthorized) is intended for use with an
        // HTTP-based authentication method we don't use, so is not correct.
        
        // For now, we just log what we got.
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("Time: ");
        sb.append(time);
        sb.append(nl);
        sb.append("Channel: ");
        sb.append(channel);
        sb.append(nl);
        sb.append(message);
        if (!message.endsWith("\n"))
            sb.append(nl);
        getServletContext().log(sb.toString());
        
        // AMF...
        sendSuccess(resp, HttpServletResponse.SC_OK);
    }
    
    private void sendSuccess(HttpServletResponse resp, int status) throws IOException {
        resp.setStatus(status);
        try (PrintWriter out = resp.getWriter()) {
            out.println("<html>");
            out.println("  <head><title>Success</title></head>");
            out.println("  <body><p>The operation completed successfully.</p></body>");
            out.println("</html>");
        }
    }
    
    /**
     * Override service so we always respond in UTF-8.
     * @param req     HttpServletRequest
     * @param resp   HttpServletResponse
     */
    protected void service(HttpServletRequest req, HttpServletResponse resp)
     throws ServletException, IOException {
        resp.setContentType("text/html; charset=UTF-8");
        super.service(req, resp);
    }
}
