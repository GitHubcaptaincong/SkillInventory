import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
// java -cp "<spring-security-crypto jar>;<spring-jcl jar>" tools/PasswordHash.java
class PasswordHash {
    public static void main(String[] args) {
        var console=System.console();
        if(console==null) throw new IllegalStateException("Interactive terminal required");
        char[] first=console.readPassword("New password: "),second=console.readPassword("Repeat password: ");
        try {
            String value=new String(first);
            if(!Arrays.equals(first,second)||value.length()<12||value.getBytes(StandardCharsets.UTF_8).length>72)
                throw new IllegalArgumentException("Passwords must match, at least 12 characters and at most 72 UTF-8 bytes");
            console.printf("BCrypt hash (protect as sensitive configuration): %s%n",new BCryptPasswordEncoder(12).encode(value));
        } finally {Arrays.fill(first,'\0');Arrays.fill(second,'\0');}
    }
}
