package name.blackcap.wxaloftapiservlet;

import java.io.File;
import java.sql.*;
import java.text.SimpleDateFormat;

/**
 * @author me@blackcap.name
 * @since 2017-11-16
 *
 * A command-line tool for purging old observations.
 */
public class PurgeTool
{
    private static final String MYNAME = "PurgeTool";
    private static final SimpleDateFormat LOCAL_TIME = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
    private static String DCONTEXT = "META-INF" + File.separator + "context.xml";

    /**
     * Entry point for the command-line utility. Syntax:
     * [-c context] days
     * Observations older than the specified number of days will be purged.
     */
    public static void main(String[] args)
    {
        /* syntax error if no arguments */
        if (args.length == 0) {
            System.err.format("%s: expecting time in days%n", MYNAME);
            System.exit(2);
        }

        /* if -c specified, it's a context file to read */
        int daysArg = 0;
        String context = DCONTEXT;
        if (args[0].equals("-c")) {
            if (args.length < 2) {
                System.err.format("%s: expecting context file name%n", MYNAME);
                System.exit(2);
            }
            context = args[1];
            daysArg = 2;
        }

        /* syntax error if no arguments beyond -c context */
        if (args.length <= daysArg) {
            System.err.format("%s: expecting time in days%n", MYNAME);
        }

        /* get number of days */
        int days = -1;
        try {
            days = Integer.parseInt(args[daysArg]);
        } catch (NumberFormatException e) {
            System.err.format("%s: invalid number: %s%n", MYNAME, args[daysArg]);
            System.exit(2);
        }

        /* sanity check */
        if (days <= 0) {
            System.err.format("%s: number of days must be positive%n", MYNAME);
            System.exit(2);
        }

        /* get connection */
        Connection conn = null;
        try {
            conn = DBUtils.getConnection(context);
            if (conn == null) {
                System.err.format("%s: unable to get connection%n", MYNAME);
                System.exit(1);
            }
        } catch (SQLException e) {
            System.err.format("%s: %s%n", MYNAME, e.getMessage());
            System.err.format("%s: unable to get connection%n", MYNAME);
            System.exit(1);
        }

        /* finally, purge */
        long since = System.currentTimeMillis() - days * 86400000L;
        System.out.println("Purging data older than " +
            LOCAL_TIME.format(new java.util.Date(since)));
        try (PreparedStatement stmt = conn.prepareStatement("delete from observations where observed < ?")) {
            stmt.setTimestamp(1, new java.sql.Timestamp(since));
            int c = stmt.executeUpdate();
            System.out.format("%d observation%s deleted%n", c, c==1? "": "s");
        } catch (SQLException e) {
            System.err.format("%s: %s%n", MYNAME, e.getMessage());
            System.err.format("%s: unable to purge data%n", MYNAME);
            System.exit(1);
        }
    }
}
