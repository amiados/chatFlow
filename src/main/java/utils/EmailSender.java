    package utils;

    // מחלקות ואובייקטים הקשורים לשליחת דואר אלקטרוני
    import javax.mail.*;
    import javax.mail.internet.*;
    import javax.mail.internet.MimeMessage;
    import javax.mail.Session;
    // מגדיר את הגדרות השרת (שם שרת, פורט, האם דרוש אימות)
    import java.io.IOException;
    import java.io.InputStream;
    import java.util.Properties;
    import java.security.SecureRandom;

    /**
     * שולח מיילים דרך SMTP, כולל ייצור ושליחת קודי OTP.
     * הטעינה של קונפיגורציית המייל מתבצעת מ-application.properties.
     */
    public class EmailSender {


        private static String FROM_EMAIL;
        private static String PASSWORD;
        private static String SMTP_HOST;
        private static String SMTP_PORT;

        // טוען את הגדרות המייל פעם אחת ב-static block
        static {
            loadEmailConfig();
        }

        /**
         * טוען מתצורת application.properties את פרטי השרת ופרטי ההתחברות.
         */
        private static void loadEmailConfig() {
            Properties props = new Properties();
            try (InputStream input = EmailSender.class.getClassLoader().getResourceAsStream("application.properties")) {
                if (input == null) {
                    throw new RuntimeException("Cannot find application.properties");
                }
                props.load(input);

                FROM_EMAIL = props.getProperty("email.from");
                PASSWORD = props.getProperty("email.password");
                SMTP_HOST = props.getProperty("email.smtp.host");
                SMTP_PORT = props.getProperty("email.smtp.port");
            } catch (IOException e) {
                throw new RuntimeException("Failed to load email configuration", e);
            }
        }

        /**
         * שולח OTP למייל המבוקש. בונה Session מאובטח ומגדיר TLS ואימות.
         * @param toEmail כתובת הנמען
         * @param otp הקוד לשליחה
         * @return true אם נשלח בהצלחה, false otherwise
         */
        public static boolean sendOTP(String toEmail, String otp) {
            try {

                // אובייקט שמכיל את כל ההגדרות עבור חיבור SMTP
                Properties props = new Properties();
                props.put("mail.smtp.auth", "true"); // יש צורך באימות כדי לשלוח המייל
                props.put("mail.smtp.starttls.enable", "true"); //מאפשר את פרוטוקול TLS כדי להבטיח חיבור מאובטח
                props.put("mail.smtp.host", SMTP_HOST); // הכתובת של שרת ה-SMTP שדרכו נשלח המייל
                props.put("mail.smtp.port", SMTP_PORT); //מספר הפורט שדרכו מתחבר לשרת (פורט של TLS)

                // אובייקט שמייצג את הסשן של הדואר האלקטרוני
                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(FROM_EMAIL, PASSWORD);
                    }
                });

                Message message = new MimeMessage(session); // אובייקט שמייצג את ההודעה שאוצים לשלוח
                message.setFrom(new InternetAddress(FROM_EMAIL)); // הגדרת כתובת השולח
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail)); // הגדרת סוג הנמען
                message.setSubject("Your OTP Code"); // הגדרת נושא ההודעה
                message.setText("Your OTP code is: " + otp); // הגדרת תוכן ההודעה

                // מבצע את שליחת המייל
                Transport.send(message);
                System.out.println("OTP sent successfully!");

                return true;
            } catch (MessagingException e) {
                e.printStackTrace();
                return false;
            }
        }

        /**
         * יוצר קוד OTP אקראי בן 6 ספרות.
         * @return מחרוזת עם קוד OTP
         */
        public static String generateOTP() {
            SecureRandom random = new SecureRandom();
            int otp = 100000 + random.nextInt(900000);  // מספר בן 6 ספרות
            return String.valueOf(otp);
        }

        /**
         * דוגמה להרצת תג OTP.
         */
        public static void main(String[] args) {
            String otp = generateOTP();
            sendOTP("amiad.ard@gmail.com", otp);
        }
    }

