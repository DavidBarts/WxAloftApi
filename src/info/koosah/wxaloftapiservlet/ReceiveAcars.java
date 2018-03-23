package info.koosah.wxaloftapiservlet;

import java.io.*;
import java.sql.*;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Formatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.TimeZone;
import javax.json.*;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import info.koosah.acarsutils.wxdecoder.WxDecoder;
import info.koosah.acarsutils.AcarsMessage;
import info.koosah.acarsutils.AcarsObservation;
import info.koosah.acarsutils.CaretNotator;


/**
 * Responsible for receiving demodulated ACARS packets from remote receivers.
 *
 * @author David Barts <n5jrn@me.com>
 */
public class ReceiveAcars extends HttpServlet {
    private static final long serialVersionUID = -8389925342660744959L;
    Charset ASCII = Charset.forName("US-ASCII");
    Charset UTF8 = Charset.forName("UTF-8");

    private static final SimpleDateFormat JSON_TIME = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXX");
    private static final SimpleDateFormat LOG_TIME = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    static {
        TimeZone gmt = TimeZone.getTimeZone("GMT");
        JSON_TIME.setTimeZone(gmt);
        LOG_TIME.setTimeZone(gmt);
    }

    /* Anything less than this value is a channel number, not a
       frequency in MHz. Note that the airband starts at 108 MHz,
       and we must keep it unambiguous which is which */
    private static final int MIN_FREQUENCY = 100;

    /* Radius of interest (in km) around each air terminal defined in areas
       table. */
    private static double RADIUS = 350.0;

    /* Logger we use. */
    private static final Logger LOGGER = Logger.getLogger(ReceiveAcars.class.getCanonicalName());

    /**
     * Process a POST request by receiving ACARS data.
     * @param req     HttpServletRequest
     * @param resp    HttpServletResponse
     */
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
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
        String auth = null;
        String time = null;
        JsonNumber channel = null;
        String message = null;
        try {
            auth = obj.getString("auth");
            if (auth == null) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing auth)");
                return;
            }
            time = obj.getString("time");
            if (time == null) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing time)");
                return;
            }
            channel = obj.getJsonNumber("channel");
            if (channel == null) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing channel)");
                return;
            }
            message = obj.getString("message");
            if (message == null) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing message)");
                return;
            }
        } catch (NullPointerException|ClassCastException e) {
            LOGGER.log(Level.WARNING, "Missing or invalid item", e);
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing or invalid item)");
            return;
        }

        // Parse the ACARS message and date/time field
        AcarsMessage parsed = new AcarsMessage(message);
        if (!parsed.parse()) {
            LOGGER.log(Level.SEVERE, "Unable to parse ACARS message " + see(message));
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (unparseable ACARS message)");
            return;
        }
        java.util.Date date = null;
        try {
            date = JSON_TIME.parse(time);
        } catch (ParseException e) {
            LOGGER.log(Level.SEVERE, "Unable to parse time " + see(time), e);
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (unparseable time)");
            return;
        }

        // The authenticator will be validated here. On failure, return a 403
        // (Forbidden) error. 401 (Unauthorized) is intended for use with an
        // HTTP-based authentication method we don't use, so is not correct.
        try (Connection conn = getConnection()) {

            // Authenticate
            String cName = null;
            int cId = -1;
            boolean logAll = false;
            boolean recordWx = false;
            try (PreparedStatement stmt = conn.prepareStatement("select id, name, log_all, record_wx from clients where auth = ?")) {
                stmt.setBytes(1, AuthTool.hash(auth));
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    cId = rs.getInt(1);
                    cName = rs.getString(2);
                    logAll = rs.getBoolean(3);
                    recordWx = rs.getBoolean(4);
                } else {
                    LOGGER.log(Level.WARNING, "Unknown authenticator " + see(auth));
                    resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden (unknown authenticator)");
                    return;
                }
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Error authenticating " + see(auth), e);
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unable to authenticate)");
                return;
            }

            // Map channel number to frequency, if needed
            int ichannel = channel.intValue();
            double frequency = 0.0;
            if (ichannel < MIN_FREQUENCY) {
                try (PreparedStatement stmt = conn.prepareStatement("select frequency from frequencies where client_id = ? and channel = ?")) {
                    stmt.setInt(1, cId);
                    stmt.setInt(2, ichannel);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        frequency = rs.getDouble(1);
                    } else {
                        LOGGER.log(Level.WARNING, String.format("No frequency for channel %d client %d (%s)", ichannel, cId, cName));
                        resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (unknown channel)");
                        return;
                    }
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, String.format("Error getting frequency for channel %d client %d (%s)", ichannel, cId, cName), e);
                    resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unable to get frequency)");
                    return;
                }
            } else {
                frequency = channel.doubleValue();
            }

            // Do actions
            if (logAll)
                logMessage(conn, parsed, cName, frequency, date);
            if (recordWx)
                recordMessage(conn, parsed, cName, frequency, date, cId);
        } catch (NamingException|SQLException e) {
            LOGGER.log(Level.SEVERE, "Unable to obtain database connection", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unable to obtain DB connection)");
            return;
        }

        // AMF...
        sendSuccess(resp, HttpServletResponse.SC_OK);
    }

    private void logMessage(Connection conn, AcarsMessage msg, String name, double freq, java.util.Date ti) {
        CaretNotator cn = new CaretNotator();
        cn.appendRaw("Received by ");
        cn.appendRaw(name);
        cn.appendRaw(" on ");
        cn.appendRaw(String.format("%.3f", freq));
        cn.appendRaw(" at ");
        cn.appendRaw(LOG_TIME.format(ti));
        cn.appendRaw("...");
        cn.appendNewline();

        if (msg.getMode() < 0x5d) {
            cn.appendRaw("Aircraft registration: ");
            cn.append(msg.getRegistration());
            cn.appendRaw(" Flight ID: ");
            seeMsgIdFlt(cn, msg.getFlightId());
            cn.appendNewline();
        }

        cn.appendRaw("Mode: ");
        cn.append(msg.getMode());
        cn.appendNewline();

        cn.appendRaw("Message label: ");
        cn.append(msg.getLabel());
        cn.appendRaw(" (");
        cn.appendRaw(msg.getLabelExplanation());
        cn.appendRaw(")");
        cn.appendNewline();

        cn.appendRaw("Block ID: ");
        cn.append(msg.getBlockId());
        cn.appendRaw(" Acknowledge: ");
        cn.append(msg.getAcknowledge());
        cn.appendNewline();

        cn.appendRaw("Message ID: ");
        seeMsgIdFlt(cn, msg.getMessageId());
        cn.appendNewline();

        if (msg.getSource() != null) {
            cn.appendRaw("Message source: ");
            cn.append(msg.getSource());
            cn.appendRaw(" (");
            cn.appendRaw(msg.getSourceExplanation());
            cn.appendRaw(")");
            cn.appendNewline();
        }

        /* the message body */
        cn.appendRaw("Message:");
        cn.appendNewline();
        cn.appendMultiline(msg.getMessage());

        /* log it */
        LOGGER.log(Level.INFO, cn.toString());
    }

    private String see(String s) {
        StringBuilder ret = new StringBuilder("\"");
        Formatter fmt = new Formatter(ret);
        int len = s.length();
        for (int i=0; i<len; i++) {
            char ch = s.charAt(i);
            switch (ch) {
            case '\t':
                ret.append("\\\t");
                break;
            case '\b':
                ret.append("\\\b");
                break;
            case '\n':
                ret.append("\\\n");
                break;
            case '\r':
                ret.append("\\\r");
                break;
            case '\f':
                ret.append("\\\f");
                break;
            case '\"':
                ret.append("\\\"");
                break;
            case '\\':
                ret.append("\\\\");
                break;
            default:
                if (ch < 32 || ch > 126) {
                    fmt.format("\\u%04x", ch);
                } else
                    ret.append(ch);
                break;
            }
        }
        fmt.flush();
        ret.append('\"');
        return ret.toString();
    }

    private void seeMsgIdFlt(CaretNotator cn, String v) {
        if (v == null)
            cn.appendRaw("(none)");
        else if (v.isEmpty())
            cn.appendRaw("(empty)");
        else
            cn.append(v);
    }

    private void recordMessage(Connection conn, AcarsMessage msg, String name, double freq, java.util.Date ti, int cl) {
        // Get flight ID, ignore message if it doesn't have one
        String flightId = msg.getFlightId();
        if (flightId == null)
            return;

        // Get the decoder for the associated airline, ignore message if
        // no decoder exists, complain if flight ID is garbage.
        WxDecoder decoder = null;
        try {
            decoder = WxDecoder.forName(flightId);
        } catch (IllegalArgumentException e) {
            /* probably just a ground to air message */
            return;
        } catch (WxDecoder.UnknownAirlineException e) {
            return;
        }

        // Decode the observations, ignore message if not coding observations.
        Iterable<AcarsObservation> observations = decoder.decode(msg, ti);
        if (observations == null)
            return;

        // Add observations to the database, silently ignoring duplicates.
        try (
            PreparedStatement stmt = conn.prepareStatement("insert into observations (received, observed, frequency, client_id, altitude, wind_speed, wind_dir, temperature, source, latitude, longitude) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            PreparedStatement stmt2 = conn.prepareStatement("insert into obs_area (observation_id, area_id) select ? as oid, id from areas where kilometers(areas.latitude, areas.longitude, ?, ?) <= ?")
        ) {
            long id = -1;
            for (AcarsObservation obs : observations) {
                // Observations must contain full spacetime coordinates to be
                // meaningful; ignore all others.
                if (obs.getObserved() == null || obs.getAltitude() == null || obs.getLatitude() == null || obs.getLongitude() == null)
                    continue;
                try {
                    stmt.setTimestamp(1, new Timestamp(ti.getTime()));
                    stmt.setTimestamp(2, new Timestamp(obs.getObserved().getTime()));
                    stmt.setDouble(3, freq);
                    stmt.setInt(4, cl);
                    stmt.setInt(5, obs.getAltitude());
                    setObject(stmt, 6, obs.getWindSpeed(), Types.SMALLINT);
                    setObject(stmt, 7, obs.getWindDirection(), Types.SMALLINT);
                    setObject(stmt, 8, obs.getTemperature(), Types.FLOAT);
                    stmt.setString(9, msg.getRegistration());
                    stmt.setDouble(10, obs.getLatitude());
                    stmt.setDouble(11, obs.getLongitude());
                    stmt.executeUpdate();
                    ResultSet rs = stmt.getGeneratedKeys();
                    if (rs.next())
                        id = rs.getLong(1);
                    else {
                        LOGGER.log(Level.SEVERE, "Error obtaining observation ID");
                        continue;
                    }
                } catch (com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException e) {
                    // Ack. This is the only way to capture and ignore an
                    // attempt to insert a duplicate record any where near
                    // unambiguously. This exception is a direct class of
                    // java.sql.SQLException! There apparently is no
                    // java.sql.* exception class related to integrity
                    // constraint violations. Sigh.
                    continue;
                } catch (SQLWarning w) {
                    LOGGER.log(Level.WARNING, "Warning inserting observation", w);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "Unable to insert observation", e);
                    continue;
                }
                try {
                    stmt2.setLong(1, id);
                    stmt2.setDouble(2, obs.getLatitude());
                    stmt2.setDouble(3, obs.getLongitude());
                    stmt2.setDouble(4, RADIUS);
                    stmt2.executeUpdate();
                } catch (SQLWarning w) {
                    LOGGER.log(Level.WARNING, "Warning inserting obs_area", w);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "Unable to insert obs_area", e);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Unable to prepare statements", e);
        }
    }

    // Set a field in a prepared statement, not being braindamaged if the
    // passed object is null.
    private void setObject(PreparedStatement stmt, int ndx, Object obj, int type)  throws SQLException {
        if (obj == null)
            stmt.setNull(ndx, type);
        else
            stmt.setObject(ndx, obj, JDBCType.valueOf(type));
    }

    private Connection getConnection() throws NamingException, SQLException {
        Context c = (Context) (new InitialContext()).lookup("java:comp/env");
        DataSource d = (DataSource) c.lookup("jdbc/WxDB");
        return d.getConnection();
    }

    private void sendSuccess(HttpServletResponse resp, int status) throws IOException {
        resp.setStatus(status);
        PrintWriter out = resp.getWriter();
        out.println("<html>");
        out.println("  <head><title>Success</title></head>");
        out.println("  <body><p>The operation completed successfully.</p></body>");
        out.println("</html>");
        out.flush();
    }

    /**
     * Override service so we always respond in UTF-8.
     * @param req     HttpServletRequest
     * @param resp    HttpServletResponse
     */
    protected void service(HttpServletRequest req, HttpServletResponse resp)
     throws ServletException, IOException {
        resp.setContentType("text/html; charset=UTF-8");
        super.service(req, resp);
    }
}
