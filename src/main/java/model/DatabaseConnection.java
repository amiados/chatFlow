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
 * מחלקת Singleton האחראית על יצירת וניהול מאגר חיבורים למסד הנתונים באמצעות HikariCP.
 * <p>
 * תבנית Singleton מוודאת שהיישום משתמש רק במופע בודד של מאגר החיבורים לאורך כל זמן הריצה.
 * </p>
 */
public class DatabaseConnection {

    /**
     * המקור למאגר החיבורים (DataSource) שמנוהל על-ידי HikariCP.
     * מאופס פעם אחת בעת טעינת המחלקה.
     */
    private static HikariDataSource dataSource;

    // בלוק אתחול סטטי שנטען פעם אחת בעת טעינת המחלקה בתאימות ל-Singleton
    static {
        try (
                // טוען את קובץ התצורה application.properties מתוך תיקיית המשאבים
                InputStream input = DatabaseConnection.class.getClassLoader()
                        .getResourceAsStream("application.properties")
        ) {
            if (input == null) {
                // אם הקובץ לא נמצא – יזרוק RuntimeException
                throw new RuntimeException("❌ לא נמצא קובץ application.properties");
            }

            // טוען מאפייני התחברות מהקובץ
            Properties properties = new Properties();
            properties.load(input);

            // קריאת כתובת ה-JDBC, שם המשתמש והסיסמה
            String url      = properties.getProperty("db.url");
            String username = properties.getProperty("db.username");
            String password = properties.getProperty("db.password");

            // הכנה והגדרה של HikariConfig
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);                    // כתובת החיבור למסד הנתונים
            config.setUsername(username);              // שם המשתמש
            config.setPassword(password);              // סיסמה
            config.setMaximumPoolSize(20);             // גודל מקסימלי של מאגר החיבורים
            config.setMinimumIdle(5);                  // מספר מינימלי של חיבורים רדומים
            config.setIdleTimeout(60000);              // זמן מרבי לחיבור רדום (במילישניות)
            config.setMaxLifetime(300000);             // זמן חיים מרבי של חיבור במאגר
            config.setConnectionTimeout(30000);        // זמן המתנה לקבלת חיבור זמין

            // אתחול המאגר עם ההגדרות שהוגדרו
            dataSource = new HikariDataSource(config);

        } catch (IOException e) {
            // במקרה של בעיה בקריאת קובץ התצורה – זריקה של RuntimeException
            throw new RuntimeException("⚠️ שגיאה בטעינת הגדרות מסד הנתונים", e);
        }
    }

    /**
     * בנאי פרטי למניעת יצירה חיצונית של מופעים נוספים (Pattern: Singleton).
     */
    private DatabaseConnection() {}

    /**
     * המופע היחיד של DatabaseConnection לשימוש כללי ביישום.
     */
    private static final DatabaseConnection INSTANCE = new DatabaseConnection();

    /**
     * מחזיר את המופע היחיד (Singleton) של המחלקה.
     *
     * @return מופע DatabaseConnection
     */
    public static DatabaseConnection getInstance() {
        return INSTANCE;
    }

    /**
     * שולף חיבור פעיל ממאגר החיבורים.
     *
     * @return Connection מ-DataSource
     * @throws SQLException אם לא ניתן לקבל חיבור
     */
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * סוגר את מאגר החיבורים (לשימוש בסיום ריצה או כיבוי השרת).
     * מדפיס הודעה לקונסול על הצלחת הסגירה.
     */
    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("🔌 החיבור למסד הנתונים נסגר.");
        }
    }
}
