package io.plottwist.auth;

import java.lang.annotation.*;
import jakarta.validation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy=StrongPassword.Check.class)
public @interface StrongPassword {
    String message() default "Password must contain 15 to 128 Unicode characters and no control characters";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Check implements ConstraintValidator<StrongPassword, String> {
        @Override public boolean isValid(String value, ConstraintValidatorContext context) {
            return AccountService.validPassword(value);
        }
    }
}
