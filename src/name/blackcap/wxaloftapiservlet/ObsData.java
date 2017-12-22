package name.blackcap.wxaloftapiservlet;

import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.format.DateTimeParseException;
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

/**
 * A production-level servlet for retrieving observation data.
 *
 * @author David Barts <n5jrn@me.com>
 */
public class ObsData extends HttpServlet {
    private static final long serialVersionUID = 3479646746764533073L;

    private static final SimpleDateFormat LOCAL_TIME = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final SimpleDateFormat UTC_TIME = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    static {
        UTC_TIME.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    private static final Logger LOGGER = Logger.getLogger(ObsDemo.class.getCanonicalName());

    /* maybe put these in a common file? or do we want separate defaults? */
    private static final String DEFAULT_DURATION = "PT2H";
    private static final long MAX_DURATION = 6L * 60L * 60L * 1000L;

    /**
     * Process a GET request by returning all appropriate observations.
     * @param req     HttpServletRequest
     * @param resp    HttpServletResponse
     */
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try (Connection conn = getConnection()) {
            doGetWithConnection(req, resp, conn);
        } catch (NamingException|SQLException e) {
            LOGGER.log(Level.SEVERE, "Unable to obtain database connection", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unable to obtain DB connection)");
            return;
        }
    }

    private void doGetWithConnection(HttpServletRequest req, HttpServletResponse resp, Connection conn) throws IOException
    {
        /* get the mandatory area ID and terminal time zone name */
        String area = req.getParameter("area");
        if (area == null) {
            LOGGER.log(Level.SEVERE, "Missing area= parameter");
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (missing area= parameter)");
            return;
        }
        int areaId = -1;
        String tzName = null;
        try {
            areaId = Integer.parseInt(area);
            try (PreparedStatement stmt = conn.prepareStatement("select timezone from areas where id = ?")) {
                stmt.setInt(1, areaId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    tzName = rs.getString(1);
                } else {
                    LOGGER.log(Level.SEVERE, "Unknown area ID " + areaId);
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (unknown area ID)");
                    return;
                }
            } catch (SQLException e2) {
                LOGGER.log(Level.SEVERE, "Unable to resolve area", e2);
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unable to resolve area)");
                return;
            }
        } catch (NumberFormatException e) {
            try (PreparedStatement stmt = conn.prepareStatement("select id, timezone from areas where name = ?")) {
                stmt.setString(1, area);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    areaId = rs.getInt(1);
                    tzName = rs.getString(2);
                } else {
                    LOGGER.log(Level.SEVERE, "Unknown area name " + area);
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (unknown area name)");
                    return;
                }
            } catch (SQLException e2) {
                LOGGER.log(Level.SEVERE, "Unable to resolve area", e2);
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unable to resolve area)");
                return;
            }
        }

        /* determine time zone to use */
        SimpleDateFormat dFormat = null;
        String zone = req.getParameter("zone");
        if (zone == null || "local".equals(zone)) {
            dFormat = (SimpleDateFormat) LOCAL_TIME.clone();
            dFormat.setTimeZone(TimeZone.getTimeZone(tzName));
        } else if ("UTC".equals(zone) || "GMT".equals(zone)) {
            dFormat = UTC_TIME;
        } else {
            dFormat = (SimpleDateFormat) LOCAL_TIME.clone();
            dFormat.setTimeZone(TimeZone.getTimeZone(zone));
        }

        /* determine how far back to go */
        String rawSince = req.getParameter("since");
        if (rawSince == null)
            rawSince = DEFAULT_DURATION;
        Duration d = null;
        try {
            d = Duration.parse(rawSince);
        } catch (DateTimeParseException e) {
            LOGGER.log(Level.SEVERE, "Invalid duration", e);
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (invalid duration)");
            return;
        }
        long millis = d.getSeconds() * 1000L + d.getNano() / 1000000;
        if (millis > MAX_DURATION) {
            LOGGER.log(Level.SEVERE, "Duration too long!");
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bad request (excessive duration)");
            return;
        }
        long since = System.currentTimeMillis() - millis;

        /* set up some objects */
        JsonArrayBuilder jaBuilder = Json.createArrayBuilder();
        final String[] FIELDS = new String[] { "received", "observed",
            "frequency", "altitude", "wind_speed", "wind_dir", "temperature",
            "source", "latitude", "longitude" };

        /* build select clause */
        StringBuilder sb = new StringBuilder();
        boolean doDelim = false;
        for (String field : FIELDS) {
            if (doDelim) sb.append(" ,");
            sb.append("observations."); sb.append(field);
            sb.append(" as "); sb.append(field);
            doDelim = true;
        }
        String clause = sb.toString();

        /* get observations */
        try (PreparedStatement stmt = conn.prepareStatement("select " + clause + " from observations join obs_area on observations.id = obs_area.observation_id where observations.observed > ? and obs_area.area_id = ?")) {
            stmt.setTimestamp(1, new Timestamp(since));
            stmt.setInt(2, areaId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JsonObjectBuilder joBuilder = Json.createObjectBuilder();
                for (String field : FIELDS) {
                    Object v = rs.getObject(field);
                    if (v instanceof Double)
                        joBuilder.add(field, (Double) v);
                    else if (v instanceof Float)
                        /* a hack to hide rounding errors */
                        joBuilder.add(field, Double.parseDouble(v.toString()));
                    else if (v instanceof BigDecimal)
                        /* not currently used but here for futureproofing */
                        joBuilder.add(field, (BigDecimal) v);
                    else if (v instanceof Number)
                        /* this works for all other number types returned by JDBC */
                        joBuilder.add(field, ((Number) v).longValue());
                    else if (v instanceof Timestamp)
                        joBuilder.add(field, dFormat.format((Timestamp) v));
                    else if (v instanceof String)
                        joBuilder.add(field, (String) v);
                    else if (v == null)
                        joBuilder.addNull(field);
                    else {
                        LOGGER.log(Level.SEVERE, "Unexpected type in observations table");
                        resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unexpected type)");
                        return;
                    }
                }
                jaBuilder.add(joBuilder);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Unable to get observations", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unable to get observations)");
            return;
        }

        /* send back JSON here */
        resp.setStatus(200);
        resp.setContentType("application/json; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println(jaBuilder.build().toString());
        out.flush();
    }

    private Connection getConnection() throws NamingException, SQLException {
        Context c = (Context) (new InitialContext()).lookup("java:comp/env");
        DataSource d = (DataSource) c.lookup("jdbc/WxDB");
        return d.getConnection();
    }
}
