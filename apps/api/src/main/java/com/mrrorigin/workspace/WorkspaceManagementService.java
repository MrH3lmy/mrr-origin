package com.mrrorigin.workspace;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class WorkspaceManagementService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final ProjectRepository projectRepository;
    private final WorkspaceContext workspaceContext;

    public WorkspaceManagementService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository memberRepository,
            ProjectRepository projectRepository,
            WorkspaceContext workspaceContext) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.projectRepository = projectRepository;
        this.workspaceContext = workspaceContext;
    }

    @Transactional
    public WorkspaceAccess createWorkspace(String name, String slug, String reportingCurrency) {
        String normalizedSlug = slug.strip().toLowerCase(Locale.ROOT);
        if (workspaceRepository.existsBySlug(normalizedSlug)) {
            throw new ResponseStatusException(CONFLICT, "Workspace slug is already in use");
        }

        String normalizedCurrency = normalizeCurrency(reportingCurrency);
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = workspaceRepository.saveAndFlush(
                new Workspace(workspaceId, name.strip(), normalizedSlug, normalizedCurrency));
        WorkspaceMember owner = memberRepository.saveAndFlush(
                new WorkspaceMember(workspaceId, workspaceContext.subjectId(), WorkspaceRole.OWNER));
        return new WorkspaceAccess(workspace, owner.role());
    }

    public List<WorkspaceAccess> listWorkspaces() {
        List<WorkspaceMember> memberships =
                memberRepository.findAllBySubjectIdOrderByCreatedAtAsc(workspaceContext.subjectId());
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<UUID, Workspace> workspaces = new HashMap<>();
        workspaceRepository.findAllByIdIn(
                        memberships.stream().map(WorkspaceMember::workspaceId).toList())
                .forEach(workspace -> workspaces.put(workspace.id(), workspace));

        return memberships.stream()
                .map(membership -> new WorkspaceAccess(
                        workspaces.get(membership.workspaceId()), membership.role()))
                .filter(access -> access.workspace() != null)
                .toList();
    }

    public WorkspaceAccess getWorkspace(UUID workspaceId) {
        WorkspaceMember membership = workspaceContext.requireMembership(workspaceId);
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        return new WorkspaceAccess(workspace, membership.role());
    }

    @Transactional
    public WorkspaceMember addMember(UUID workspaceId, String subjectId, WorkspaceRole role) {
        WorkspaceMember manager = workspaceContext.requireManager(workspaceId);
        String normalizedSubject = subjectId.strip();

        if (role == WorkspaceRole.OWNER && manager.role() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(FORBIDDEN, "Only an owner can add another owner");
        }
        if (memberRepository.existsByWorkspaceIdAndSubjectId(workspaceId, normalizedSubject)) {
            throw new ResponseStatusException(CONFLICT, "Workspace member already exists");
        }

        return memberRepository.saveAndFlush(new WorkspaceMember(workspaceId, normalizedSubject, role));
    }

    public List<WorkspaceMember> listMembers(UUID workspaceId) {
        workspaceContext.requireMembership(workspaceId);
        return memberRepository.findAllByWorkspaceIdOrderByCreatedAtAsc(workspaceId);
    }

    public WorkspaceMember getMember(UUID workspaceId, String subjectId) {
        workspaceContext.requireMembership(workspaceId);
        return memberRepository
                .findByWorkspaceIdAndSubjectId(workspaceId, subjectId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace member not found"));
    }

    @Transactional
    public Project createProject(UUID workspaceId, String name, String domain, String timezone) {
        workspaceContext.requireManager(workspaceId);
        String normalizedDomain = normalizeDomain(domain);
        String normalizedTimezone = normalizeTimezone(timezone);

        if (projectRepository.existsByWorkspaceIdAndDomain(workspaceId, normalizedDomain)) {
            throw new ResponseStatusException(CONFLICT, "Project domain already exists in this workspace");
        }

        return projectRepository.saveAndFlush(new Project(
                UUID.randomUUID(),
                workspaceId,
                name.strip(),
                normalizedDomain,
                generatePublicKey(),
                normalizedTimezone));
    }

    public List<Project> listProjects(UUID workspaceId) {
        workspaceContext.requireMembership(workspaceId);
        return projectRepository.findAllByWorkspaceIdOrderByCreatedAtAsc(workspaceId);
    }

    public Project getProject(UUID workspaceId, UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        return projectRepository
                .findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Project not found"));
    }

    /**
     * The project's configured IANA timezone id, for callers outside this package that need it for
     * presentation/reporting boundaries (#26's weekly summary) without reaching into {@link Project}'s
     * package-private fields directly.
     */
    public String projectTimezone(UUID workspaceId, UUID projectId) {
        return getProject(workspaceId, projectId).timezone();
    }

    /**
     * Workspace members eligible by default to receive the weekly summary email (#59, per
     * {@code docs/weekly-summary-delivery-plan.md} §2a/B2): manage-level role (OWNER/ADMIN) with a
     * captured email address. A member with no captured email yet (see {@link WorkspaceMember}'s
     * {@code email} field) is silently excluded -- an operational gap, never a failed delivery.
     * Unlike every other method here, this is called from the scheduler's own background thread, not
     * a per-caller authenticated request, so it does not go through {@link WorkspaceContext}.
     */
    public List<WeeklySummaryRecipient> listWeeklySummaryRecipients(UUID workspaceId) {
        return memberRepository.findAllByWorkspaceIdOrderByCreatedAtAsc(workspaceId).stream()
                .filter(member -> member.role().canManage())
                .filter(member -> member.email() != null && !member.email().isBlank())
                .map(member -> new WeeklySummaryRecipient(member.subjectId(), member.email()))
                .toList();
    }

    public record WeeklySummaryRecipient(String subjectId, String email) {}

    /**
     * Every project system-wide, for the weekly-summary scheduler's own tick (#59), which has no
     * authenticated caller/workspace context to scope a membership check against -- unlike every other
     * method here. Returns a plain projection rather than the {@link Project} entity, whose accessors
     * are package-private.
     */
    public List<SchedulableProject> listAllProjectsForScheduling() {
        return projectRepository.findAll().stream()
                .map(project -> new SchedulableProject(project.workspaceId(), project.id(), project.name(), project.timezone()))
                .toList();
    }

    public record SchedulableProject(UUID workspaceId, UUID projectId, String projectName, String timezone) {}

    /**
     * A single project's name, for the scheduler's rendered email subject (#59) -- unlike {@link
     * #getProject}, does not require an authenticated {@link WorkspaceContext}, since the scheduler
     * runs on a background thread with no caller. Falls back to a generic label rather than throwing
     * if the project has since been deleted between claiming a delivery and sending it.
     */
    public String projectNameForScheduling(UUID workspaceId, UUID projectId) {
        return projectRepository.findByIdAndWorkspaceId(projectId, workspaceId).map(Project::name).orElse("your project");
    }

    private static String normalizeCurrency(String reportingCurrency) {
        String currency = reportingCurrency == null || reportingCurrency.isBlank()
                ? "USD"
                : reportingCurrency.strip().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(currency).getCurrencyCode();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid reporting currency");
        }
    }

    private static String normalizeDomain(String domain) {
        String normalized = domain.strip().toLowerCase(Locale.ROOT);
        if (!DOMAIN_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid project domain");
        }
        return normalized;
    }

    private static String normalizeTimezone(String timezone) {
        String normalized = timezone == null || timezone.isBlank() ? "UTC" : timezone.strip();
        try {
            return ZoneId.of(normalized).getId();
        } catch (DateTimeException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid project timezone");
        }
    }

    private static String generatePublicKey() {
        byte[] random = new byte[24];
        SECURE_RANDOM.nextBytes(random);
        return "pk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public record WorkspaceAccess(Workspace workspace, WorkspaceRole role) {}
}
