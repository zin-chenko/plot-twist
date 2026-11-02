package io.plottwist.auth.security.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

/**
 * Server-rendered feedback without exposing authentication provider errors or submitted
 * credentials.
 */
@Controller
public class LoginPageController {
  public static final String ERROR_MESSAGE =
      "Неверный логин или пароль. Проверьте данные и попробуйте снова.";

  @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  ResponseEntity<String> login(
      HttpServletRequest request, @RequestAttribute("_csrf") CsrfToken csrf) {
    boolean failed = request.getParameterMap().containsKey("error");
    String context = HtmlUtils.htmlEscape(request.getContextPath());
    String error =
        failed
            ? "<div id=\"login-error\" class=\"alert alert-danger\" role=\"alert\">"
                + ERROR_MESSAGE
                + "</div>"
            : "";
    String invalid = failed ? "aria-invalid=\"true\" aria-describedby=\"login-error\"" : "";
    String page =
        """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Вход — Plot Twist</title>
          <link href="%s/default-ui.css" rel="stylesheet">
        </head>
        <body>
          <div class="content">
            <form class="login-form" method="post" action="%s/login">
              <h2>Вход в аккаунт</h2>
              %s
              <p>
                <label for="username">Логин или email</label>
                <input type="text" id="username" name="username" autocomplete="username" required autofocus %s>
              </p>
              <p>
                <label for="password">Пароль</label>
                <input type="password" id="password" name="password" autocomplete="current-password" required %s>
              </p>
              <input type="hidden" name="%s" value="%s">
              <button type="submit" class="primary">Sign in</button>
            </form>
          </div>
        </body>
        </html>
        """
            .formatted(
                context,
                context,
                error,
                invalid,
                invalid,
                HtmlUtils.htmlEscape(csrf.getParameterName()),
                HtmlUtils.htmlEscape(csrf.getToken()));
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noStore())
        .body(page);
  }
}
