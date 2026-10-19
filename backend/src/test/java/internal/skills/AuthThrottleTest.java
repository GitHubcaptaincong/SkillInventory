package internal.skills;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.security.authentication.*;

class AuthThrottleTest {
    @Test void attemptsAreBoundedWithoutUnboundedClientState() {
        var manager=mock(AuthenticationManager.class);
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("invalid"));
        var controller=new AuthController(manager,null,null);
        for(int i=0;i<20;i++) {
            assertThatThrownBy(() -> controller.login(new AuthController.Login("unknown","test-password"),null,null))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(401));
        }
        assertThatThrownBy(() -> controller.login(new AuthController.Login("unknown","test-password"),null,null))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(429));
        verify(manager,times(20)).authenticate(any());
    }
}
