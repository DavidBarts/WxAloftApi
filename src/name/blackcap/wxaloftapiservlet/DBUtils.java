package name.blackcap.wxaloftapiservlet;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

/**
 * @author me@blackcap.name
 * @since 2017-12-14
 *
 * Miscellaneous database-related routines.
 */
public class DBUtils
{
    /**
     * Given the path to a context.xml file, locate the first <Resource>
     * tag that pretains to a class in the javax.sql.DataSource hierarchy,
     * and use the information therein to obtain a database connection.
     * Intended for use with command-line utilities, as it prints errors
     * to standard output.
     *
     * @param name      Name of a context.xml file
     * @return          java.sql.Connection object, null on error
     */
    public static Connection getConnection(String name) throws SQLException
    {
        /* get name to use in error messages */
        String myname = new Exception().getStackTrace()[1].getClassName();
        int dot = myname.lastIndexOf('.');
        if (dot != -1) {
            dot += 1;
            if (dot < myname.length())
                myname = myname.substring(dot);
        }

        /* parse document */
        Node doc = null;
        try {
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(name));
        } catch (IOException|SAXException|ParserConfigurationException e) {
            System.err.format("%s: %s%n", myname, e.getMessage());
            return null;
        }

        /* drop down to root node */
        Node n = doc.getFirstChild();
        while (n != null) {
            if (nodeIs(n, "Context"))
                break;
            n = n.getNextSibling();
        }
        if (n == null) {
            System.err.format("%s: no root Context node found!%n", myname);
            return null;
        }
        doc = n;

        /* locate the first suitable Resource node */
        NamedNodeMap attrs = null;
        n = doc.getFirstChild();
        while (n != null) {
            if (nodeIs(n, "Resource")) {
                attrs = n.getAttributes();
                if ("javax.sql.DataSource".equals(getAttribute(attrs, "type")))
                    break;
            }
            n = n.getNextSibling();
        }
        if (n == null) {
            System.err.format("%s: no suitable Resource node found%s%n", myname);
            return null;
        }

        /* get data for connect string */
        String url = getAttribute(attrs, "url");
        if (url == null) {
            System.err.format("%s: no database URL found%n", myname);
            return null;
        }
        String username = getAttribute(attrs, "username");
        if (username == null) {
            System.err.format("%s: no database username found%n", myname);
            return null;
        }
        String password = getAttribute(attrs, "password");
        if (password == null) {
            System.err.format("%s: no database password found%n", myname);
            return null;
        }

        /* return a connection */
        Properties props = new Properties();
        props.put("user", username);
        props.put("password", password);
        return DriverManager.getConnection(url, props);
    }

    private static boolean nodeIs(Node n, String s)
    {
        if (!(n instanceof Element))
            return false;
        String myName = ((Element) n).getTagName();
        if (myName == null)
            return false;
        int colon = myName.lastIndexOf(':');
        if (colon > -1)
            myName = myName.substring(colon + 1);
        return myName.equals(s);
    }

    private static String getAttribute(NamedNodeMap attrs, String name)
    {
        Attr raw = (Attr) attrs.getNamedItem(name);
        if (raw == null)
            return null;
        return raw.getValue();
    }
}