package info.koosah.wxaloftapiservlet;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.*;
import java.sql.*;

/**
 * @author n5jrn@me.com
 * @since 2017-11-16
 *
 * Both a command-line tool for managing authenticators and a callable
 * routine to hash plaintext authenticators.
 */
public class AuthTool
{
    private static final String MYNAME = "AuthTool";
    private static String DCONTEXT = "META-INF" + File.separator + "context.xml";
    private static final Charset ASCII = Charset.forName("US-ASCII");

    // All the printable ASCII ones except space and backslash, which might
    // cause headaches in .properties files. This is used to generate both
    // plaintext and hashed authenticators.
    private static byte[] ACHARS = "!\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abcdefghijklmnopqrstuvwxyz{|}~".getBytes(ASCII);

    // This is the number of characters from ACHARS we need to have to
    // represent the number of bits of randomness in a hash (i.e. to
    // represent the hash as a radix ACHARS.length string).
    private static int ALENGTH = 40;

    /**
     * Utility routine to hash an authenticator. Currently this makes
     * something printable because a) we have the space, and b) it's nicer
     * at the mysql> prompt.
     * @param       Unhashed authenticator.
     * @return      Hashed authenticator.
     */
    public static byte[] hash(byte[] unhashed)
    {
        /* obtain a digest object */
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        /* use it to hash the authenticator */
        md.reset();
        BigInteger hash = new BigInteger(1, md.digest(unhashed));

        /* return a radix ACHARS.length string */
        BigInteger base = BigInteger.valueOf((long) ACHARS.length);
        byte[] ret = new byte[ALENGTH];
        for (int i=0; i<ALENGTH; i++) {
            BigInteger[] result = hash.divideAndRemainder(base);
            ret[ALENGTH-i-1] = ACHARS[result[1].intValue()];
            hash = result[0];
        }
        return ret;
    }

    /**
     * String version of the authenticator-hasher.
     * @param       Unhashed authenticator.
     * @return      Hashed authenticator.
     */
    public static byte[] hash(String unhashed)
    {
        return hash(unhashed.getBytes(ASCII));
    }

    /**
     * Entry point for the command-line utility. Syntax:
     * [-c context] client [authenticator]
     * Client may be specified by name or ID.
     */
    public static void main(String[] args) throws Exception
    {
        // Parse arguments

        /* syntax error if no arguments */
        if (args.length == 0) {
            noclient();
        }

        /* if -c specified, it's a context file to read */
        int client = 0;
        String context = DCONTEXT;
        if (args[0].equals("-c")) {
            if (args.length < 2) {
                System.err.format("%s: expecting context file name%n", MYNAME);
                System.exit(2);
            }
            context = args[1];
            client = 2;
        }

        /* syntax error if no arguments beyond -c context */
        if (args.length <= client) {
            noclient();
        }

        /* next arg is a client ID or name */
        String clientName = null;
        int clientId = -1;
        try {
            clientId = Integer.parseInt(args[client]);
        } catch (NumberFormatException e) {
            clientName = args[client];
        }

        /* next argument is an optional authenticator to use; randomize
           one if none specified */
        byte[] auth = null;
        if (args.length == client + 1) {
            auth = generate();
        } else if (args.length == client + 2) {
            auth = args[client+1].getBytes(ASCII);
        } else {
            System.err.format("%s: too many arguments%n", MYNAME);
            System.exit(2);
        }

        /* open a database connection */
        Connection conn = DBUtils.getConnection(context);
        if (conn == null) {
            System.err.format("%s: unable to obtain database connection%n", MYNAME);
            System.exit(1);
        }
        conn.setAutoCommit(true);

        /* obtain missing client info, die if client is not there */
        int locationId = -1;
        if (clientName != null) {
            try (PreparedStatement stmt = conn.prepareStatement("select id, location_id from clients where name = ?")) {
                stmt.setString(1, clientName);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    clientId = rs.getInt(1);
                    locationId = rs.getInt(2);
                } else {
                    System.err.format("%s: unknown client name \"%s\"%n", MYNAME, clientName);
                    System.exit(1);
                }
            }
        } else {
            try (PreparedStatement stmt = conn.prepareStatement("select name, location_id from clients where id = ?")) {
                stmt.setInt(1, clientId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    clientName = rs.getString(1);
                    locationId = rs.getInt(2);
                } else {
                    System.err.format("%s: unknown client ID %d%n", MYNAME, clientId);
                    System.exit(1);
                }
            }
        }

        /* obtain and print out full client info */
        System.out.format("Client ID: %d%n", clientId);
        System.out.format("Client name: %s%n%n", clientName);
        try (PreparedStatement stmt = conn.prepareStatement("select line1, line2, line3, city, region, postcode, country from locations where id = ?")) {
            stmt.setInt(1, locationId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                printLine(rs.getString(1));
                printLine(rs.getString(2));
                printLine(rs.getString(3));
                System.out.format("%s, %s %s %s%n%n", rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7));
            } else {
                System.err.format("%s: unknown location ID %d!%n", MYNAME, locationId);
                System.exit(1);
            }
        }

        /* confirm what we're about to do */
        System.out.format("New authenticator: %s%n", new String(auth, ASCII));
        System.out.print("OK? ");
        System.out.flush();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String conf = stdin.readLine().trim().toLowerCase();
        if (!(conf.startsWith("t") || conf.startsWith("y"))) {
            System.err.format("%s: aborted!%n", MYNAME);
            System.exit(1);
        }

        /* update database */
        try (PreparedStatement stmt = conn.prepareStatement("update clients set auth = ? where id = ?")) {
            stmt.setBytes(1, hash(auth));
            stmt.setInt(2, clientId);
            stmt.executeUpdate();
        }

        /* AMF */
        System.out.println("Authenticator changed.");
        System.exit(0);
    }

    private static void printLine(String s)
    {
        if (s != null)
            System.out.println(s);
    }

    private static void noclient()
    {
        System.err.format("%s: expecting client ID or name%n", MYNAME);
        System.exit(2);
    }

    private static byte[] generate()
    {
        byte[] ret = new byte[ALENGTH];
        SecureRandom randomizer = new SecureRandom();
        for (int i=0; i<ret.length; i++)
            ret[i] = ACHARS[randomizer.nextInt(ACHARS.length)];
        return ret;
    }
}
