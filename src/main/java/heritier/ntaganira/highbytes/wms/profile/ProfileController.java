package heritier.ntaganira.highbytes.wms.profile;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.profile
 * - File       : ProfileController.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : My profile: the signed-in user's account, roles and rights, their password and their photo
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.admin.user.UserService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.security.PasswordRules;
import heritier.ntaganira.highbytes.wms.security.PasswordService;
import heritier.ntaganira.highbytes.wms.security.SessionAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Duration;

/**
 * The signed-in user's own account. Everyone may see what they hold, change
 * their own password and set their own photo; nothing else about the
 * account is theirs to change, because an account whose holder edits its
 * access is the control failure this system exists to prevent. A photo
 * grants nothing.
 *
 * <p>The page also shows the account's sign-ins and the changes made to it.
 * An administrator issues temporary passwords and so could sign in as
 * anyone once; the holder is the one person who would notice a sign-in or
 * a password change they did not make.
 */
@Controller
@RequestMapping("/profile")
public class ProfileController {

    private final UserService users;
    private final PasswordService passwords;
    private final SessionAccess sessions;
    private final AuditService audit;
    private final ProfilePhotoService photos;

    public ProfileController(UserService users, PasswordService passwords, SessionAccess sessions,
                             AuditService audit, ProfilePhotoService photos) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.audit = audit;
        this.photos = photos;
    }

    @GetMapping
    public String profile(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("me", users.detail(me.id()).orElseThrow());
        model.addAttribute("assignments", users.assignments(me.id()).stream()
                .filter(a -> a.revocable())
                .toList());
        model.addAttribute("held", users.heldPermissions(me.id()));
        model.addAttribute("signIns", audit.historyOf("sign_in", me.id(), 10));
        model.addAttribute("history", audit.historyOf("app_user", me.id(), 20));
        return "profile/view";
    }

    @GetMapping("/password")
    public String passwordForm(@AuthenticationPrincipal AppUserDetails me, Model model) {
        return passwordView(me, model);
    }

    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal AppUserDetails me,
                                 PasswordForm form,
                                 @ModelAttribute("currentBranch") BranchView branch,
                                 HttpServletRequest request,
                                 HttpServletResponse response,
                                 Model model,
                                 RedirectAttributes redirect) {

        var outcome = passwords.changeOwn(form.getCurrentPassword(), form.getNewPassword(),
                form.getConfirmPassword(), branch);
        if (outcome.lockedOut()) {
            // Whoever is at the screen does not know the password: the lock
            // ended every session of the account, this one first.
            sessions.end(request);
            return "redirect:/login?locked";
        }
        if (!outcome.changed()) {
            // The page lists what is wrong. The passwords typed are never sent back.
            model.addAttribute("problems", outcome.problems());
            return passwordView(me, model);
        }

        // The change ended every session of the account; keep this one.
        sessions.refresh(request, response, me.branchId());
        redirect.addFlashAttribute("flashSuccess",
                "Password changed. Any other session of your account ends on its next page.");
        return "redirect:/";
    }

    /**
     * The signed-in user's photo. Pages ask for it with its version in the
     * address, so a browser may keep it: a new photo is a new address. Kept
     * private, never in a shared cache.
     */
    @GetMapping("/photo")
    public ResponseEntity<byte[]> photo() {
        return photos.mine()
                .map(p -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate())
                        .eTag(p.version())
                        .body(p.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/photo")
    public String uploadPhoto(@RequestParam(name = "photo", required = false) MultipartFile upload,
                              @ModelAttribute("currentBranch") BranchView branch,
                              RedirectAttributes redirect) {
        try {
            // Refused before it is read: the size is known from the request.
            if (upload != null && upload.getSize() > PhotoProcessor.MAX_UPLOAD_BYTES) {
                throw new PhotoRejectedException("That photo is over 5 MB. Choose a smaller one.");
            }
            byte[] bytes = upload == null ? new byte[0] : upload.getBytes();
            boolean changed = photos.replaceMine(bytes, branch);
            redirect.addFlashAttribute("flashSuccess", changed
                    ? "Photo saved. It is cut to a square from the centre."
                    : "That is already your photo.");
        } catch (PhotoRejectedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (IOException e) {
            redirect.addFlashAttribute("flashError", "The photo did not arrive whole. Try again.");
        }
        return "redirect:/profile";
    }

    @PostMapping("/photo/remove")
    public String removePhoto(@ModelAttribute("currentBranch") BranchView branch, RedirectAttributes redirect) {
        if (photos.removeMine(branch)) {
            redirect.addFlashAttribute("flashSuccess", "Photo removed. Your initials show instead.");
        }
        return "redirect:/profile";
    }

    /** Someone who must change their password sees nothing else, so the page stands alone. */
    private String passwordView(AppUserDetails me, Model model) {
        model.addAttribute("minLength", PasswordRules.MIN_LENGTH);
        model.addAttribute("maxLength", PasswordRules.MAX_LENGTH);
        return me.mustChangePassword() ? "profile/password-required" : "profile/password";
    }
}
