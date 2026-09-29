package heritier.ntaganira.highbytes.wms.common.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.web
 * - File       : LoginController.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Serves the login page; Spring Security handles the POST
 * </pre>
 */

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The login page; Spring Security handles the POST. */
@Controller
public class LoginController {

    private final int lockoutMinutes;

    public LoginController(@Value("${highbytes.security.lockout-minutes:15}") int lockoutMinutes) {
        this.lockoutMinutes = lockoutMinutes;
    }

    @GetMapping("/login")
    public String login(Model model) {
        // A locked account is told how long the lock lasts, from the same setting that sets it.
        model.addAttribute("lockoutMinutes", lockoutMinutes);
        return "login";
    }
}
