package com.sky.decisioncompanion.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.decisioncompanion.common.BusinessException;
import com.sky.decisioncompanion.common.ResultCode;
import com.sky.decisioncompanion.model.ProfileEmotion;
import com.sky.decisioncompanion.model.ProfileFear;
import com.sky.decisioncompanion.model.ProfileMemoryAuditLog;
import com.sky.decisioncompanion.model.ProfileMemoryCandidate;
import com.sky.decisioncompanion.model.ProfileRelationship;
import com.sky.decisioncompanion.model.ProfileSceneMemoryLink;
import com.sky.decisioncompanion.model.ProfileValues;
import com.sky.decisioncompanion.repository.ProfileEmotionRepository;
import com.sky.decisioncompanion.repository.ProfileFearRepository;
import com.sky.decisioncompanion.repository.ProfileMemoryAuditLogRepository;
import com.sky.decisioncompanion.repository.ProfileMemoryCandidateRepository;
import com.sky.decisioncompanion.repository.ProfileRelationshipRepository;
import com.sky.decisioncompanion.repository.ProfileSceneMemoryLinkRepository;
import com.sky.decisioncompanion.repository.ProfileValuesRepository;
import com.sky.decisioncompanion.service.memory.ProfileSceneMemoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class ProfileMemoryGovernanceService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_EXPIRED = "expired";
    private static final String LINK_ACTIVE = "active";
    private static final String LINK_DELETED = "deleted";
    private static final String LINK_DELETE_FAILED = "delete_failed";

    private final ProfileMemoryCandidateRepository candidateRepository;
    private final ProfileMemoryAuditLogRepository auditLogRepository;
    private final ProfileValuesRepository valuesRepository;
    private final ProfileEmotionRepository emotionRepository;
    private final ProfileRelationshipRepository relationshipRepository;
    private final ProfileFearRepository fearRepository;
    private final ProfileSceneMemoryLinkRepository linkRepository;
    private final ProfileSceneMemoryService sceneMemoryService;
    private final ObjectMapper objectMapper;

    public ProfileMemoryGovernanceService(
            ProfileMemoryCandidateRepository candidateRepository,
            ProfileMemoryAuditLogRepository auditLogRepository,
            ProfileValuesRepository valuesRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            ProfileSceneMemoryLinkRepository linkRepository,
            ProfileSceneMemoryService sceneMemoryService,
            ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.auditLogRepository = auditLogRepository;
        this.valuesRepository = valuesRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
        this.linkRepository = linkRepository;
        this.sceneMemoryService = sceneMemoryService;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Transactional
    public ProfileMemoryCandidate createCandidate(MemoryCandidateCommand command) {
        validateUser(command == null ? null : command.userId());
        String profileType = normalizeProfileType(command.profileType());
        validateRequired(command.subject(), command.content());

        LocalDateTime now = LocalDateTime.now();
        ProfileMemoryCandidate candidate = new ProfileMemoryCandidate();
        candidate.setUserId(command.userId());
        candidate.setProfileType(profileType);
        candidate.setSubject(command.subject().trim());
        candidate.setContent(command.content().trim());
        candidate.setDetail(clean(command.detail()));
        candidate.setConfidence(ProfileWritePolicy.normalizeConfidence(command.confidence()));
        candidate.setEvidence(toJson(command.evidence()));
        candidate.setSource(clean(command.source()));
        candidate.setSourceConversationId(command.sourceConversationId());
        candidate.setStatus(STATUS_PENDING);
        candidate.setExpiresAt(now.plusDays(7));
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        candidateRepository.insert(candidate);
        return candidate;
    }

    public List<ProfileMemoryCandidate> listPendingCandidates(Long userId) {
        validateUser(userId);
        LocalDateTime now = LocalDateTime.now();
        return candidateRepository.selectList(new LambdaQueryWrapper<ProfileMemoryCandidate>()
                .eq(ProfileMemoryCandidate::getUserId, userId)
                .eq(ProfileMemoryCandidate::getStatus, STATUS_PENDING)
                .gt(ProfileMemoryCandidate::getExpiresAt, now)
                .orderByDesc(ProfileMemoryCandidate::getCreatedAt));
    }

    public int countPendingCandidates(Long userId) {
        validateUser(userId);
        LocalDateTime now = LocalDateTime.now();
        Long count = candidateRepository.selectCount(new LambdaQueryWrapper<ProfileMemoryCandidate>()
                .eq(ProfileMemoryCandidate::getUserId, userId)
                .eq(ProfileMemoryCandidate::getStatus, STATUS_PENDING)
                .gt(ProfileMemoryCandidate::getExpiresAt, now));
        return Math.toIntExact(count == null ? 0L : count);
    }

    public List<ProfileMemoryAuditLog> listAuditLogs(Long userId, Integer limit) {
        validateUser(userId);
        int boundedLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        return auditLogRepository.selectList(new LambdaQueryWrapper<ProfileMemoryAuditLog>()
                .eq(ProfileMemoryAuditLog::getUserId, userId)
                .orderByDesc(ProfileMemoryAuditLog::getCreatedAt)
                .last("LIMIT " + boundedLimit));
    }

    @Transactional
    public GovernanceResult writeConfirmedMemory(ConfirmedMemoryCommand command) {
        validateUser(command == null ? null : command.userId());
        String profileType = normalizeProfileType(command.profileType());
        validateRequired(command.subject(), command.content());

        FormalWriteResult formal = writeFormalProfile(new FormalWriteCommand(
                command.userId(),
                profileType,
                command.subject(),
                command.content(),
                command.detail(),
                command.confidence(),
                command.evidence(),
                command.source(),
                command.sourceConversationId(),
                command.userMessage()));
        writeAudit(command.userId(), profileType, formal.profileRecordId(), null, "confirm",
                null, formal.profile(), null);

        return new GovernanceResult(true, "confirm", profileType, formal.profileRecordId(), null, "画像记忆已写入");
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public GovernanceResult confirmCandidate(Long userId, Long candidateId) {
        ProfileMemoryCandidate candidate = loadPendingCandidate(userId, candidateId);
        String beforeSnapshot = toAuditJson(candidate);
        List<String> evidence = parseEvidence(candidate.getEvidence());
        FormalWriteResult formal = writeFormalProfile(new FormalWriteCommand(
                userId,
                candidate.getProfileType(),
                candidate.getSubject(),
                candidate.getContent(),
                candidate.getDetail(),
                candidate.getConfidence(),
                evidence,
                candidate.getSource(),
                candidate.getSourceConversationId(),
                candidate.getContent()));

        LocalDateTime now = LocalDateTime.now();
        candidate.setStatus(STATUS_CONFIRMED);
        candidate.setHandledAt(now);
        candidate.setUpdatedAt(now);
        candidateRepository.updateById(candidate);
        writeAudit(userId, candidate.getProfileType(), formal.profileRecordId(), candidate.getId(), "confirm",
                beforeSnapshot, toAuditJson(formal.profile()), null);

        return new GovernanceResult(true, "confirm", candidate.getProfileType(),
                formal.profileRecordId(), candidate.getId(), "待确认记忆已确认并写入");
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public GovernanceResult rejectCandidate(Long userId, Long candidateId, String reason) {
        ProfileMemoryCandidate candidate = loadPendingCandidate(userId, candidateId);
        String beforeSnapshot = toAuditJson(candidate);
        LocalDateTime now = LocalDateTime.now();
        candidate.setStatus(STATUS_REJECTED);
        candidate.setHandledAt(now);
        candidate.setUpdatedAt(now);
        candidateRepository.updateById(candidate);
        writeAudit(userId, candidate.getProfileType(), null, candidate.getId(), "reject",
                beforeSnapshot, "{}", reason);
        return new GovernanceResult(true, "reject", candidate.getProfileType(), null,
                candidate.getId(), "待确认记忆已拒绝");
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public GovernanceResult correctCandidate(Long userId, Long candidateId, MemoryCorrectionCommand command) {
        ProfileMemoryCandidate candidate = loadPendingCandidate(userId, candidateId);
        String beforeSnapshot = toAuditJson(candidate);
        validateRequired(command == null ? null : command.subject(), command == null ? null : command.content());
        List<String> evidence = parseEvidence(candidate.getEvidence());
        FormalWriteResult formal = writeFormalProfile(new FormalWriteCommand(
                userId,
                candidate.getProfileType(),
                command.subject(),
                command.content(),
                command.detail(),
                candidate.getConfidence(),
                evidence,
                candidate.getSource(),
                candidate.getSourceConversationId(),
                command.content()));

        LocalDateTime now = LocalDateTime.now();
        candidate.setStatus(STATUS_CONFIRMED);
        candidate.setHandledAt(now);
        candidate.setUpdatedAt(now);
        candidateRepository.updateById(candidate);
        writeAudit(userId, candidate.getProfileType(), formal.profileRecordId(), candidate.getId(), "correct",
                beforeSnapshot, toAuditJson(formal.profile()), command.reason());
        return new GovernanceResult(true, "correct", candidate.getProfileType(),
                formal.profileRecordId(), candidate.getId(), "待确认记忆已修正并写入");
    }

    @Transactional
    public GovernanceResult correctProfile(
            Long userId,
            String profileType,
            Long profileRecordId,
            MemoryCorrectionCommand command) {
        validateUser(userId);
        String normalizedType = normalizeProfileType(profileType);
        validateRequired(command == null ? null : command.subject(), command == null ? null : command.content());
        String reason = command.reason() == null ? "" : command.reason().trim();
        List<String> evidence = StringUtils.hasText(reason) ? List.of(reason) : List.of();

        Object before = loadOwnedProfile(userId, normalizedType, profileRecordId);
        String beforeSnapshot = toAuditJson(before);
        deactivateProfile(normalizedType, before);
        deleteActiveLinks(userId, normalizedType, profileRecordId);
        FormalWriteResult formal = writeFormalProfile(new FormalWriteCommand(
                userId,
                normalizedType,
                command.subject(),
                command.content(),
                command.detail(),
                BigDecimal.ONE,
                evidence,
                "profile_governance",
                null,
                command.content()));
        writeAudit(userId, normalizedType, formal.profileRecordId(), null, "correct",
                beforeSnapshot, toAuditJson(formal.profile()), reason);
        return new GovernanceResult(true, "correct", normalizedType,
                formal.profileRecordId(), null, "画像记录已修正");
    }

    @Transactional
    public GovernanceResult deleteProfile(Long userId, String profileType, Long profileRecordId, String reason) {
        validateUser(userId);
        String normalizedType = normalizeProfileType(profileType);
        Object before = loadOwnedProfile(userId, normalizedType, profileRecordId);
        String beforeSnapshot = toAuditJson(before);
        deactivateProfile(normalizedType, before);
        deleteActiveLinks(userId, normalizedType, profileRecordId);
        writeAudit(userId, normalizedType, profileRecordId, null, "delete", beforeSnapshot, "{}", reason);
        return new GovernanceResult(true, "delete", normalizedType, profileRecordId, null, "画像记录已删除");
    }

    private FormalWriteResult writeFormalProfile(FormalWriteCommand command) {
        LocalDateTime now = LocalDateTime.now();
        String evidenceJson = toJson(command.evidence());
        Object profile;
        Long profileRecordId;

        switch (command.profileType()) {
            case "value" -> {
                ProfileValues value = new ProfileValues();
                value.setUserId(command.userId());
                value.setActive(true);
                value.setItem(command.subject().trim());
                value.setPreference(command.content().trim());
                value.setConfidence(ProfileWritePolicy.normalizeConfidence(command.confidence()));
                value.setEvidence(evidenceJson);
                value.setUpdatedAt(now);
                valuesRepository.insert(value);
                profile = value;
                profileRecordId = value.getId();
            }
            case "emotion" -> {
                ProfileEmotion emotion = new ProfileEmotion();
                emotion.setUserId(command.userId());
                emotion.setActive(true);
                emotion.setEmotion(command.subject().trim());
                emotion.setBehavior(command.content().trim());
                emotion.setTriggerDesc(clean(command.detail()));
                emotion.setAgentNote(String.join("\n", cleanList(command.evidence())));
                emotion.setUpdatedAt(now);
                emotionRepository.insert(emotion);
                profile = emotion;
                profileRecordId = emotion.getId();
            }
            case "relationship" -> {
                ProfileRelationship relationship = new ProfileRelationship();
                relationship.setUserId(command.userId());
                relationship.setActive(true);
                relationship.setName(command.subject().trim());
                relationship.setNote(command.content().trim());
                relationship.setRole(clean(command.detail()));
                relationship.setUpdatedAt(now);
                relationshipRepository.insert(relationship);
                profile = relationship;
                profileRecordId = relationship.getId();
            }
            case "fear", "boundary" -> {
                ProfileFear fear = new ProfileFear();
                fear.setUserId(command.userId());
                fear.setActive(true);
                fear.setType(command.profileType());
                fear.setDescription(command.subject().trim());
                fear.setManifestation(command.content().trim());
                fear.setConfidence(ProfileWritePolicy.normalizeConfidence(command.confidence()));
                fear.setEvidence(evidenceJson);
                if ("boundary".equals(command.profileType())) {
                    fear.setBoundaryType(clean(command.detail()));
                }
                fear.setUpdatedAt(now);
                fearRepository.insert(fear);
                profile = fear;
                profileRecordId = fear.getId();
            }
            default -> throw new BusinessException(ResultCode.UNSUPPORTED_PROFILE_TYPE);
        }

        writeSceneMemory(command, profileRecordId);
        return new FormalWriteResult(profile, profileRecordId);
    }

    private void writeSceneMemory(FormalWriteCommand command, Long profileRecordId) {
        ProfileSceneMemoryService.SceneMemoryWriteResult result = sceneMemoryService.saveSceneMemory(
                new ProfileSceneMemoryService.SceneMemoryWrite(
                        command.userId(),
                        defaultIfBlank(command.userMessage(), command.content()),
                        defaultIfBlank(command.source(), "profile_governance"),
                        1,
                        List.of(command.profileType()),
                        cleanList(command.evidence()),
                        List.of(sceneSignal(command.profileType(), command.subject(), command.content())),
                        command.profileType(),
                        ProfileWritePolicy.normalizeConfidence(command.confidence()),
                        command.sourceConversationId(),
                        profileRecordId));
        for (String documentId : result.documentIds()) {
            ProfileSceneMemoryLink existingLink = linkRepository.selectOne(
                    new LambdaQueryWrapper<ProfileSceneMemoryLink>()
                            .eq(ProfileSceneMemoryLink::getDocumentId, documentId));
            if (existingLink == null) {
                ProfileSceneMemoryLink link = new ProfileSceneMemoryLink();
                link.setUserId(command.userId());
                link.setProfileType(command.profileType());
                link.setProfileRecordId(profileRecordId);
                link.setDocumentId(documentId);
                link.setSource(defaultIfBlank(command.source(), "profile_governance"));
                link.setActive(true);
                link.setDeleteStatus(LINK_ACTIVE);
                linkRepository.insert(link);
                continue;
            }
            existingLink.setUserId(command.userId());
            existingLink.setProfileType(command.profileType());
            existingLink.setProfileRecordId(profileRecordId);
            existingLink.setSource(defaultIfBlank(command.source(), "profile_governance"));
            existingLink.setActive(true);
            existingLink.setDeleteStatus(LINK_ACTIVE);
            existingLink.setUpdatedAt(LocalDateTime.now());
            linkRepository.updateById(existingLink);
        }
    }

    private ProfileMemoryCandidate loadPendingCandidate(Long userId, Long candidateId) {
        validateUser(userId);
        if (candidateId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        ProfileMemoryCandidate candidate = candidateRepository.selectById(candidateId);
        if (candidate == null || !userId.equals(candidate.getUserId())) {
            throw new BusinessException(ResultCode.PROFILE_MEMORY_NOT_FOUND);
        }
        if (!STATUS_PENDING.equals(candidate.getStatus())) {
            throw new BusinessException(ResultCode.PROFILE_MEMORY_ALREADY_HANDLED);
        }
        if (candidate.getExpiresAt() != null && candidate.getExpiresAt().isBefore(LocalDateTime.now())) {
            LocalDateTime now = LocalDateTime.now();
            candidate.setStatus(STATUS_EXPIRED);
            candidate.setHandledAt(now);
            candidate.setUpdatedAt(now);
            candidateRepository.updateById(candidate);
            throw new BusinessException(ResultCode.PROFILE_MEMORY_EXPIRED);
        }
        candidate.setProfileType(normalizeProfileType(candidate.getProfileType()));
        validateRequired(candidate.getSubject(), candidate.getContent());
        return candidate;
    }

    private Object loadOwnedProfile(Long userId, String profileType, Long profileRecordId) {
        if (profileRecordId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        Object profile = switch (profileType) {
            case "value" -> valuesRepository.selectById(profileRecordId);
            case "emotion" -> emotionRepository.selectById(profileRecordId);
            case "relationship" -> relationshipRepository.selectById(profileRecordId);
            case "fear", "boundary" -> fearRepository.selectById(profileRecordId);
            default -> throw new BusinessException(ResultCode.UNSUPPORTED_PROFILE_TYPE);
        };
        if (!isOwnedActiveProfile(userId, profileType, profile)) {
            throw new BusinessException(ResultCode.PROFILE_RECORD_NOT_FOUND);
        }
        return profile;
    }

    private boolean isOwnedActiveProfile(Long userId, String profileType, Object profile) {
        if (profile instanceof ProfileValues value) {
            return userId.equals(value.getUserId()) && Boolean.TRUE.equals(value.getActive());
        }
        if (profile instanceof ProfileEmotion emotion) {
            return userId.equals(emotion.getUserId()) && Boolean.TRUE.equals(emotion.getActive());
        }
        if (profile instanceof ProfileRelationship relationship) {
            return userId.equals(relationship.getUserId()) && Boolean.TRUE.equals(relationship.getActive());
        }
        if (profile instanceof ProfileFear fear) {
            return userId.equals(fear.getUserId())
                    && Boolean.TRUE.equals(fear.getActive())
                    && profileType.equals(fear.getType());
        }
        return false;
    }

    private void deactivateProfile(String profileType, Object profile) {
        LocalDateTime now = LocalDateTime.now();
        if (profile instanceof ProfileValues value) {
            value.setActive(false);
            value.setUpdatedAt(now);
            valuesRepository.updateById(value);
            return;
        }
        if (profile instanceof ProfileEmotion emotion) {
            emotion.setActive(false);
            emotion.setUpdatedAt(now);
            emotionRepository.updateById(emotion);
            return;
        }
        if (profile instanceof ProfileRelationship relationship) {
            relationship.setActive(false);
            relationship.setUpdatedAt(now);
            relationshipRepository.updateById(relationship);
            return;
        }
        if (profile instanceof ProfileFear fear) {
            fear.setActive(false);
            fear.setUpdatedAt(now);
            fearRepository.updateById(fear);
            return;
        }
        throw new BusinessException(ResultCode.PROFILE_RECORD_NOT_FOUND);
    }

    private void deleteActiveLinks(Long userId, String profileType, Long profileRecordId) {
        List<ProfileSceneMemoryLink> links = linkRepository.selectList(new LambdaQueryWrapper<ProfileSceneMemoryLink>()
                .eq(ProfileSceneMemoryLink::getUserId, userId)
                .eq(ProfileSceneMemoryLink::getProfileType, profileType)
                .eq(ProfileSceneMemoryLink::getProfileRecordId, profileRecordId)
                .eq(ProfileSceneMemoryLink::getActive, true));
        for (ProfileSceneMemoryLink link : links) {
            boolean deleted = sceneMemoryService.deleteSceneMemory(link.getDocumentId());
            link.setActive(false);
            link.setDeleteStatus(deleted ? LINK_DELETED : LINK_DELETE_FAILED);
            link.setUpdatedAt(LocalDateTime.now());
            linkRepository.updateById(link);
        }
    }

    private void writeAudit(
            Long userId,
            String profileType,
            Long profileRecordId,
            Long candidateId,
            String action,
            Object before,
            Object after,
            String reason) {
        writeAudit(userId, profileType, profileRecordId, candidateId, action,
                toAuditJson(before), toAuditJson(after), reason);
    }

    private void writeAudit(
            Long userId,
            String profileType,
            Long profileRecordId,
            Long candidateId,
            String action,
            String beforeSnapshot,
            String afterSnapshot,
            String reason) {
        ProfileMemoryAuditLog auditLog = new ProfileMemoryAuditLog();
        auditLog.setUserId(userId);
        auditLog.setProfileType(profileType);
        auditLog.setProfileRecordId(profileRecordId);
        auditLog.setCandidateId(candidateId);
        auditLog.setAction(action);
        auditLog.setBeforeSnapshot(beforeSnapshot);
        auditLog.setAfterSnapshot(afterSnapshot);
        auditLog.setReason(reason);
        auditLog.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(auditLog);
    }

    private String normalizeProfileType(String profileType) {
        if (!StringUtils.hasText(profileType)) {
            throw new BusinessException(ResultCode.UNSUPPORTED_PROFILE_TYPE);
        }
        return switch (profileType.trim().toLowerCase(Locale.ROOT)) {
            case "value", "values" -> "value";
            case "emotion", "emotions" -> "emotion";
            case "relationship", "relationships" -> "relationship";
            case "fear", "fears" -> "fear";
            case "boundary", "boundaries" -> "boundary";
            default -> throw new BusinessException(ResultCode.UNSUPPORTED_PROFILE_TYPE);
        };
    }

    private void validateUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
    }

    private void validateRequired(String subject, String content) {
        if (!StringUtils.hasText(subject) || !StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(cleanList(values));
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String toAuditJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private List<String> parseEvidence(String evidence) {
        if (!StringUtils.hasText(evidence)) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(evidence);
        } catch (JsonProcessingException e) {
            return List.of(evidence);
        }
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private String sceneSignal(String profileType, String subject, String content) {
        return "%s:%s:%s".formatted(profileType, subject.trim(), content.trim());
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    public record MemoryCandidateCommand(
            Long userId,
            String profileType,
            String subject,
            String content,
            String detail,
            BigDecimal confidence,
            List<String> evidence,
            String source,
            Long sourceConversationId) {
    }

    public record MemoryCorrectionCommand(String subject, String content, String detail, String reason) {
    }

    public record ConfirmedMemoryCommand(
            Long userId,
            String profileType,
            String subject,
            String content,
            String detail,
            BigDecimal confidence,
            List<String> evidence,
            String source,
            Long sourceConversationId,
            String userMessage) {
    }

    public record GovernanceResult(
            boolean success,
            String action,
            String profileType,
            Long profileRecordId,
            Long candidateId,
            String message) {
    }

    private record FormalWriteCommand(
            Long userId,
            String profileType,
            String subject,
            String content,
            String detail,
            BigDecimal confidence,
            List<String> evidence,
            String source,
            Long sourceConversationId,
            String userMessage) {
    }

    private record FormalWriteResult(Object profile, Long profileRecordId) {
    }
}
