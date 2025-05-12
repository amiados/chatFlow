package model;

// ייבוא המחלקות הנדרשות מ-HikariCP, ספרייה לניהול יעיל של מאגרי חיבורים
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * מחלקת Singleton שמנהלת את החיבור למסד הנתונים באמצעות HikariCP.
 */
public class DatabaseConnection {

    // מופע סטטי של מאגר החיבורים
    private static HikariDataSource dataSource;

    // בלוק אתחול סטטי שמתבצע פעם אחת כאשר המחלקה נטענת
    static {
        try (
                // טוען את קובץ ההגדרות application.properties מתוך קובץ ה-JAR או תיקיית resources
             InputStream input = DatabaseConnection.class.getClassLoader()
                     .getResourceAsStream("application.properties")
        ) {

            // אם לא נמצא קובץ ההגדרות – זרוק שגיאה
            if (input == null) {
                throw new RuntimeException("❌ לא נמצא קובץ application.properties");
            }

            // ייבוא מאפייני התחברות יכול להתבצע כאן דרך הקובץ
            Properties properties = new Properties();
            properties.load(input);

            // הגדרת כתובת JDBC ישירה למסד נתונים מסוג SQL Server עם שם בסיס הנתונים CHAT
            String url = properties.getProperty("db.url");

            // שם משתמש וסיסמה לגישה למסד הנתונים
            String username = properties.getProperty("db.username");
            String password = properties.getProperty("db.password");

            // יצירת קונפיגורציית Hikari עם הפרטים
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);                    // כתובת JDBC
            config.setUsername(username);              // שם משתמש למסד הנתונים
            config.setPassword(password);              // סיסמת משתמש
            config.setMaximumPoolSize(20);             // מספר מקסימלי של חיבורים במאגר
            config.setMinimumIdle(5);                  // מספר מינימלי של חיבורים רדומים
            config.setIdleTimeout(60000);              // זמן מקסימלי שחיבור יכול להיות רדום (במילישניות)
            config.setMaxLifetime(300000);             // זמן חיים מקסימלי של חיבור במאגר
            config.setConnectionTimeout(30000);        // זמן מקסימלי לחכות לחיבור זמין

            // אתחול מאגר החיבורים עם ההגדרות שסיפקנו
            dataSource = new HikariDataSource(config);

        } catch (IOException e) {
            // אם יש בעיה בקריאת קובץ ההגדרות – זרוק שגיאה כללית
            throw new RuntimeException("⚠️ שגיאה בטעינת הגדרות מסד הנתונים", e);
        }
    }

    // בנאי פרטי – מונע יצירת מופעים נוספים של המחלקה (Pattern: Singleton)
    private DatabaseConnection() {}

    // מופע בודד של המחלקה (Singleton instance)
    private static final DatabaseConnection INSTANCE = new DatabaseConnection();

    /**
     * מחזיר את המופע היחיד של המחלקה (גישה ל-Singleton)
     */
    public static DatabaseConnection getInstance() {
        return INSTANCE;
    }

    /**
     * מחזיר חיבור פעיל ממאגר החיבורים
     * @return Connection למסד הנתונים
     * @throws SQLException אם לא ניתן להשיג חיבור
     */
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * סוגר את מאגר החיבורים (לרוב יקרה בעת כיבוי השרת/אפליקציה)
     */
    public static void close() {
        if(dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("🔌 החיבור למסד הנתונים נסגר.");
        }
    }
}
