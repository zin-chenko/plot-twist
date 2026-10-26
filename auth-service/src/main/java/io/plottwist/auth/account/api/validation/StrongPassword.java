package io.plottwist.auth.account.api.validation;

import io.plottwist.auth.account.model.PasswordPolicy;
import jakarta.validation.*;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Check.class)
public @interface StrongPassword {
  String message() default
      "Password must contain 15 to 128 Unicode characters and no control characters";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Check implements ConstraintValidator<StrongPassword, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return PasswordPolicy.isValid(value);
    }
  }
}
