import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.annotation.*;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


@WebServlet(name = "AlbumServlet", value = "/AlbumServlet")
public class AlbumServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(AlbumServlet.class.getName());

    String jdbcUrl = "jdbc:postgresql://database-1.ca3v2jyutj60.us-west-2.rds.amazonaws.com:5432/postgres";
    String username = "postgres";
    String password = "postgres";

    private HikariDataSource dataSource;

    public AlbumServlet() {
        super();
    }

    @Override
    public void init() {
        // Initialize connection pool
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(50);
        dataSource = new HikariDataSource(config);
    }

    @Override
    public void destroy() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    static class Album {
        public byte[] image;
        public Profile profile;
    }

    public static class Profile {
        public String artist;
        public String title;
        public String year;

        // You can add a method here to parse the JSON and fill the attributes
        void parseFromJson(String jsonData) throws IOException {
            ObjectMapper mapper = new ObjectMapper(); // Jackson's JSON processor
            Profile parsedProfile = mapper.readValue(jsonData, Profile.class);
            this.artist = parsedProfile.artist;
            this.year = parsedProfile.year;
            this.title = parsedProfile.title;
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Set the response content type
        response.setContentType("application/json");
        PrintWriter out = response.getWriter();
        ObjectMapper objectMapper = new ObjectMapper();
        try (Connection connection = dataSource.getConnection()) {
        // try {
            // Extract album ID from the request URL parameters
            String pathInfo = request.getPathInfo();  // This should give "/7" based on your URL
            if (pathInfo == null || pathInfo.isEmpty()) {
                out.write(objectMapper.writeValueAsString(Map.of("error", "albumId path parameter is required")));
                return;
            }

            String[] split  = pathInfo.split("/");  // Removes the leading slash to get "7"
            String albumIdParam = split[split.length - 1];
//            System.out.println(pathInfo);
//            System.out.println(albumIdParam);

            // Parse the album ID
            int albumId = Integer.parseInt(albumIdParam);

            //Class.forName("org.postgresql.Driver");
            //Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
            String query = "SELECT * FROM albums WHERE albumID = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setInt(1, albumId);

            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String artist = resultSet.getString("artist");
                String title = resultSet.getString("title");
                String year = resultSet.getString("year");
                Profile profile = new Profile();
                profile.artist = artist;
                profile.title = title;
                profile.year = year;
                String jsonResponse = objectMapper.writeValueAsString(profile);
                out.write(jsonResponse);
            } else {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);  // 404 status code
                out.write(objectMapper.writeValueAsString(Map.of("error", "Album not found")));
            }
        } catch (NumberFormatException e) {
            out.write(objectMapper.writeValueAsString(Map.of("error", e.getStackTrace())));
        } catch (Exception e) {
            out.write(objectMapper.writeValueAsString(Map.of("error", e.getStackTrace())));
        }
    }


    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Set the response content type
        response.setContentType("application/json");
        PrintWriter out = response.getWriter();

        try (Connection connection = dataSource.getConnection()) {
        // try {
            // Check if the request is multipart
            if (!ServletFileUpload.isMultipartContent(request)) {
                out.println("Error: Form must have enctype=multipart/form-data.");
                return;
            }

            // Parse the request to get file items.
            ServletFileUpload upload = new ServletFileUpload();

            // Parse the request to get the form data
            FileItemIterator iter = upload.getItemIterator(request);

            byte[] imageBytes = null;
            Profile profile = new Profile();

            while (iter.hasNext()) {
                FileItemStream item = iter.next();
                String name = item.getFieldName();
                InputStream stream = item.openStream();

                if (!item.isFormField()) {
                    if ("image".equals(name)) {
                        imageBytes = IOUtils.toByteArray(stream);  // Apache Commons IO
                    }
                } else {
                    if ("profile".equals(name)) {
                        String profileJson = IOUtils.toString(stream, StandardCharsets.UTF_8);
                        profile.parseFromJson(profileJson);
                    }
                }
            }
            //logger.log(Level.SEVERE, "here-1");
            //Class.forName("org.postgresql.Driver");
            //Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
            //logger.log(Level.SEVERE, "here0");
            String insertAlbumQuery = "INSERT INTO albums (artist, title, year) VALUES (?, ?, ?) RETURNING albumID";
            PreparedStatement albumStatement = connection.prepareStatement(insertAlbumQuery);
            albumStatement.setString(1, profile.artist);
            albumStatement.setString(2, profile.title);
            albumStatement.setString(3, profile.year);

            ResultSet albumResult = albumStatement.executeQuery();

            int albumID = -1;
            if (albumResult.next()) {
                albumID = albumResult.getInt("albumID");
            }
            //logger.log(Level.SEVERE, "here1");

            String insertImageQuery = "INSERT INTO images (albumID, imageData) VALUES (?, ?)";
            PreparedStatement imageStatement = connection.prepareStatement(insertImageQuery);
            imageStatement.setInt(1, albumID);
            imageStatement.setBytes(2, imageBytes);

            imageStatement.executeUpdate();
            //logger.log(Level.SEVERE, "here2");
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("albumId", albumID);
            responseData.put("imageSize", imageBytes.length);

            // Convert the Map to a JSON string
            ObjectMapper objectMapper = new ObjectMapper();
            String jsonResponse = objectMapper.writeValueAsString(responseData);
            //logger.log(Level.SEVERE, "here3");
            // Set the response type to JSON and write the response
            out.write(jsonResponse);

        } catch (Exception ex) {
            out.println("File Upload Error: " + ex.getStackTrace());
            logger.log(Level.SEVERE, "My error: " + ex.getStackTrace());
            ex.printStackTrace();
        }
    }
}
