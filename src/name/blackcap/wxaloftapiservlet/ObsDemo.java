package name.blackcap.wxaloftapiservlet;

import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.text.SimpleDateFormat;
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
 * This is a DEMO servlet that retrieves all the observations close enough
 * to pertain to Seattle (as defined in the obs_area table). A better servlet
 * that reads area, time zone (field local or UTC) and how far back
 * to go is in the plans; this is just so we can get something out the door
 * ASAP for now.
 *
 * @author David Barts <n5jrn@me.com>
 */
public class ObsDemo extends HttpServlet {
    private static final long serialVersionUID = -6495957736914153355L;

    private static final SimpleDateFormat LOCAL_TIME = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final SimpleDateFormat UTC_TIME = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    static {
        UTC_TIME.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    private static final Logger LOGGER = Logger.getLogger(ObsDemo.class.getCanonicalName());
    private static final String LOCATION = "KSEA";
    /* two hours back, cf. java.time.Duration */
    private static final long DURATION = -7200000;

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
        /* get location ID and time zone for formatting */
        int areaId = -1;
        SimpleDateFormat dFormat = (SimpleDateFormat) LOCAL_TIME.clone();
        try (PreparedStatement stmt = conn.prepareStatement("select id, timezone from areas where name = ?")) {
            stmt.setString(1, LOCATION);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                areaId = rs.getInt(1);
                dFormat.setTimeZone(TimeZone.getTimeZone(rs.getString(2)));
            } else {
                LOGGER.log(Level.SEVERE, String.format("Location \"%s\" unknown!", LOCATION));
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (location unknown)");
                return;
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Unable to resolve location", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error (unable to resolve location)");
            return;
        }

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
            Timestamp since = new Timestamp(DURATION + System.currentTimeMillis());
            stmt.setTimestamp(1, since);
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
