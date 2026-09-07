package com.sky.decisioncompanion.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

@Service
public class ProfileMemoryGovernanceService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileMemoryGovernanceService.class);

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
    private final EmbeddingModel embeddingModel;

    /** 候选近重复语义去重阈值（embedding 余弦相似度）。 */
    @Value("${decision-companion.memory.governance.near-duplicate-threshold:0.85}")
    private Double nearDuplicateThreshold;

    public ProfileMemoryGovernanceService(
            ProfileMemoryCandidateRepository candidateRepository,
            ProfileMemoryAuditLogRepository auditLogRepository,
            ProfileValuesRepository valuesRepository,
            ProfileEmotionRepository emotionRepository,
            ProfileRelationshipRepository relationshipRepository,
            ProfileFearRepository fearRepository,
            ProfileSceneMemoryLinkRepository linkRepository,
            ProfileSceneMemoryService sceneMemoryService,
            ObjectMapper objectMapper,
            EmbeddingModel embeddingModel) {
        this.candidateRepository = candidateRepository;
        this.auditLogRepository = auditLogRepository;
        this.valuesRepository = valuesRepository;
        this.emotionRepository = emotionRepository;
        this.relationshipRepository = relationshipRepository;
        this.fearRepository = fearRepository;
        this.linkRepository = linkRepository;
        this.sceneMemoryService = sceneMemoryService;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.embeddingModel = embeddingModel;
    }

    @Transactional
    public ProfileMemoryCandidate createCandidate(MemoryCandidateCommand command) {
        validateUser(command == null ? null : command.userId());
        String profileType = normalizeProfileType(command.profileType());
        validateRequired(command.subject(), command.content());

        LocalDateTime now = LocalDateTime.now();
        String subject = clean(command.subject());
        String content = clean(command.content());
        String detail = clean(command.detail());
        BigDecimal confidence = ProfileWritePolicy.normalizeConfidence(command.confidence());
        ProfileMemoryCandidate existing = findEquivalentPendingCandidate(
                command.userId(), profileType, subject, content, detail, now);
        if (existing != null) {
            mergePendingCandidate(existing, command, now, true);
            return existing;
        }
        // 语义近重复：LLM 换措辞产生的重复候选合并（embedding 失败降级为纯文本去重，不阻塞写入）
        ProfileMemoryCandidate nearDuplicate = findNearDuplicateCandidate(
                command.userId(), profileType, subject, content, now);
        if (nearDuplicate != null) {
            mergePendingCandidate(nearDuplicate, command, now, false);
            return nearDuplicate;
        }

        ProfileMemoryCandidate candidate = new ProfileMemoryCandidate();
        candidate.setUserId(command.userId());
        candidate.setProfileType(profileType);
        candidate.setSubject(subject);
        candidate.setContent(content);
        candidate.setDetail(detail);
        candidate.setConfidence(confidence);
        candidate.setEvidence(toJson(command.evidence()));
        candidate.setSource(clean(command.source()));
        candidate.setSourceConversationId(command.sourceConversationId());
        candidate.setInputHash(command.inputHash());
        candidate.setStatus(STATUS_PENDING);
        candidate.setExpiresAt(now.plusDays(7));
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        candidateRepository.insert(candidate);
        return candidate;
    }

    /**
     * 合并候选（精确重复 {@code adoptText=true} 时采用新文本；语义近重复 {@code adoptText=false}
     * 保留原候选文本为规范，只合并置信度上限与证据去重，避免措辞反复横跳）。
     */
    private void mergePendingCandidate(
            ProfileMemoryCandidate existing,
            MemoryCandidateCommand command,
            LocalDateTime now,
            boolean adoptText) {
        if (adoptText) {
            existing.setProfileType(normalizeProfileType(command.profileType()));
            existing.setSubject(clean(command.subject()));
            existing.setContent(clean(command.content()));
            existing.setDetail(clean(command.detail()));
        }
        existing.setConfidence(max(existing.getConfidence(), command.confidence()));
        existing.setEvidence(toJson(mergeEvidence(parseEvidence(existing.getEvidence()), command.evidence())));
        if (StringUtils.hasText(command.source())) {
            existing.setSource(command.source().trim());
        }
        if (command.sourceConversationId() != null) {
            existing.setSourceConversationId(command.sourceConversationId());
        }
        existing.setStatus(STATUS_PENDING);
        existing.setUpdatedAt(now);
        candidateRepository.updateById(existing);
    }

    /**
     * 语义近重复匹配：对未精确命中的候选，与同用户同类型待确认候选做 embedding 余弦相似度比对，
     * 找到 >= {@link #nearDuplicateThreshold()} 的最相似行。嵌入计算失败时降级返回 null（回退纯文本去重）。
     */
    private ProfileMemoryCandidate findNearDuplicateCandidate(
            Long userId, String profileType, String subject, String content, LocalDateTime now) {
        double threshold = nearDuplicateThreshold();
        if (threshold <= 0.0 || embeddingModel == null) {
            return null;
        }
        List<ProfileMemoryCandidate> candidates = candidateRepository.selectList(
                new LambdaQueryWrapper<ProfileMemoryCandidate>()
                        .eq(ProfileMemoryCandidate::getUserId, userId)
                        .eq(ProfileMemoryCandidate::getProfileType, profileType)
                        .eq(ProfileMemoryCandidate::getStatus, STATUS_PENDING)
                        .gt(ProfileMemoryCandidate::getExpiresAt, now)
                        .orderByAsc(ProfileMemoryCandidate::getCreatedAt)
                        .last("LIMIT 50"));
        if (candidates.isEmpty()) {
            return null;
        }
        // 排除已命中的精确重复，避免重复计算
        List<ProfileMemoryCandidate> distinct = candidates.stream()
                .filter(candidate -> !normalizeKey(candidate.getSubject()).equals(normalizeKey(subject))
                        || !normalizeKey(candidate.getContent()).equals(normalizeKey(content)))
                .toList();
        if (distinct.isEmpty()) {
            return null;
        }
        String queryText = subject + "：" + content;
        try {
            float[] queryVector = embeddingModel.embed(queryText);
            List<String> candidateTexts = distinct.stream()
                    .map(candidate -> clean(candidate.getSubject()) + "：" + clean(candidate.getContent()))
                    .toList();
            List<float[]> candidateVectors = embeddingModel.embed(candidateTexts);
            double best = threshold;
            int bestIndex = -1;
            for (int i = 0; i < distinct.size(); i++) {
                float[] candidateVector = candidateVectors.get(i);
                if (candidateVector == null) {
                    continue;
                }
                double similarity = cosineSimilarity(queryVector, candidateVector);
                if (similarity >= best) {
                    best = similarity;
                    bestIndex = i;
                }
            }
            if (bestIndex >= 0) {
                ProfileMemoryCandidate matched = distinct.get(bestIndex);
                logger.info("候选近重复语义去重合并, userId: {}, profileType: {}, subject: {}, 命中: {}, similarity: {}",
                        userId, profileType, subject, clean(matched.getSubject()),
                        String.format(Locale.ROOT, "%.3f", best));
                return matched;
            }
        } catch (Exception e) {
            logger.warn("候选近重复语义去重嵌入计算失败，降级为纯文本去重, userId: {}, profileType: {}",
                    userId, profileType, e);
        }
        return null;
    }

    private double cosineSimilarity(float[] first, float[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) {
            return 0.0;
        }
        double dot = 0.0;
        double firstNorm = 0.0;
        double secondNorm = 0.0;
        for (int i = 0; i < first.length; i++) {
            dot += (double) first[i] * second[i];
            firstNorm += (double) first[i] * first[i];
            secondNorm += (double) second[i] * second[i];
        }
        if (firstNorm == 0.0 || secondNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(firstNorm) * Math.sqrt(secondNorm));
    }

    private double nearDuplicateThreshold() {
        return nearDuplicateThreshold == null ? 0.80 : nearDuplicateThreshold;
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

    /**
     * 定时回收过期候选：把 status=pending 且已超过 expires_at 的候选批量标记为 expired。
     * 避免过期候选长期以 pending 状态残留（懒标记只覆盖被访问到的候选），返回本次标记数量。
     */
    @Scheduled(fixedDelayString = "${decision-companion.memory.governance.expire-scan-ms:3600000}")
    public int expireCandidates() {
        LocalDateTime now = LocalDateTime.now();
        int updated = candidateRepository.update(null, new LambdaUpdateWrapper<ProfileMemoryCandidate>()
                .eq(ProfileMemoryCandidate::getStatus, STATUS_PENDING)
                .lt(ProfileMemoryCandidate::getExpiresAt, now)
                .set(ProfileMemoryCandidate::getStatus, STATUS_EXPIRED)
                .set(ProfileMemoryCandidate::getHandledAt, now)
                .set(ProfileMemoryCandidate::getUpdatedAt, now));
        if (updated > 0) {
            logger.info("回收过期画像候选: {}", updated);
        }
        return updated;
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
                null, formal.profile(), null, command.gateVerdict());

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
                beforeSnapshot, toAuditJson(formal.profile()), null, null);

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
                beforeSnapshot, "{}", reason, null);
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
                beforeSnapshot, toAuditJson(formal.profile()), command.reason(), null);
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
                beforeSnapshot, toAuditJson(formal.profile()), reason, null);
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
        writeAudit(userId, normalizedType, profileRecordId, null, "delete", beforeSnapshot, "{}", reason, null);
        return new GovernanceResult(true, "delete", normalizedType, profileRecordId, null, "画像记录已删除");
    }

    private FormalWriteResult writeFormalProfile(FormalWriteCommand command) {
        LocalDateTime now = LocalDateTime.now();
        String evidenceJson = toJson(command.evidence());
        Object profile;
        Long profileRecordId;

        switch (command.profileType()) {
            case "value" -> {
                ProfileValues value = findValue(command.userId(), command.subject());
                boolean insert = value == null;
                if (insert) {
                    value = new ProfileValues();
                }
                value.setUserId(command.userId());
                value.setActive(true);
                value.setItem(clean(command.subject()));
                value.setPreference(clean(command.content()));
                value.setConfidence(ProfileWritePolicy.normalizeConfidence(command.confidence()));
                value.setEvidence(evidenceJson);
                value.setUpdatedAt(now);
                if (insert) {
                    valuesRepository.insert(value);
                } else {
                    valuesRepository.updateById(value);
                }
                profile = value;
                profileRecordId = value.getId();
            }
            case "emotion" -> {
                ProfileEmotion emotion = findEmotion(command.userId(), command.subject(), command.detail());
                boolean insert = emotion == null;
                if (insert) {
                    emotion = new ProfileEmotion();
                    emotion.setUserId(command.userId());
                }
                emotion.setActive(true);
                emotion.setEmotion(clean(command.subject()));
                emotion.setBehavior(clean(command.content()));
                emotion.setTriggerDesc(clean(command.detail()));
                emotion.setAgentNote(String.join("\n", cleanList(command.evidence())));
                emotion.setUpdatedAt(now);
                if (insert) {
                    emotionRepository.insert(emotion);
                } else {
                    emotionRepository.updateById(emotion);
                }
                profile = emotion;
                profileRecordId = emotion.getId();
            }
            case "relationship" -> {
                ProfileRelationship relationship = findRelationship(command.userId(), command.subject());
                boolean insert = relationship == null;
                if (insert) {
                    relationship = new ProfileRelationship();
                    relationship.setUserId(command.userId());
                }
                relationship.setActive(true);
                relationship.setName(clean(command.subject()));
                relationship.setNote(clean(command.content()));
                relationship.setRole(clean(command.detail()));
                relationship.setUpdatedAt(now);
                if (insert) {
                    relationshipRepository.insert(relationship);
                } else {
                    relationshipRepository.updateById(relationship);
                }
                profile = relationship;
                profileRecordId = relationship.getId();
            }
            case "fear", "boundary" -> {
                ProfileFear fear = findFear(command.userId(), command.profileType(), command.subject());
                boolean insert = fear == null;
                if (insert) {
                    fear = new ProfileFear();
                    fear.setUserId(command.userId());
                }
                fear.setActive(true);
                fear.setType(command.profileType());
                fear.setDescription(clean(command.subject()));
                fear.setManifestation(clean(command.content()));
                fear.setConfidence(ProfileWritePolicy.normalizeConfidence(command.confidence()));
                fear.setEvidence(evidenceJson);
                if ("boundary".equals(command.profileType())) {
                    fear.setBoundaryType(clean(command.detail()));
                }
                fear.setUpdatedAt(now);
                if (insert) {
                    fearRepository.insert(fear);
                } else {
                    fearRepository.updateById(fear);
                }
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
            ProfileSceneMemoryLink existingLink = findLink(documentId);
            if (existingLink == null) {
                ProfileSceneMemoryLink link = new ProfileSceneMemoryLink();
                link.setUserId(command.userId());
                link.setProfileType(command.profileType());
                link.setProfileRecordId(profileRecordId);
                link.setDocumentId(documentId);
                link.setSource(defaultIfBlank(command.source(), "profile_governance"));
                link.setActive(true);
                link.setDeleteStatus(LINK_ACTIVE);
                try {
                    linkRepository.insert(link);
                } catch (DuplicateKeyException e) {
                    ProfileSceneMemoryLink concurrentLink = findLink(documentId);
                    if (concurrentLink == null) {
                        throw e;
                    }
                    updateLink(concurrentLink, command, profileRecordId);
                }
                continue;
            }
            updateLink(existingLink, command, profileRecordId);
        }
    }

    private ProfileSceneMemoryLink findLink(String documentId) {
        return linkRepository.selectOne(new LambdaQueryWrapper<ProfileSceneMemoryLink>()
                .eq(ProfileSceneMemoryLink::getDocumentId, documentId));
    }

    private void updateLink(ProfileSceneMemoryLink link, FormalWriteCommand command, Long profileRecordId) {
        link.setUserId(command.userId());
        link.setProfileType(command.profileType());
        link.setProfileRecordId(profileRecordId);
        link.setSource(defaultIfBlank(command.source(), "profile_governance"));
        link.setActive(true);
        link.setDeleteStatus(LINK_ACTIVE);
        link.setUpdatedAt(LocalDateTime.now());
        linkRepository.updateById(link);
    }

    private ProfileMemoryCandidate findEquivalentPendingCandidate(
            Long userId,
            String profileType,
            String subject,
            String content,
            String detail,
            LocalDateTime now) {
        return safeRepositoryList(candidateRepository.selectList(new LambdaQueryWrapper<ProfileMemoryCandidate>()
                .eq(ProfileMemoryCandidate::getUserId, userId)
                .eq(ProfileMemoryCandidate::getProfileType, profileType)
                .eq(ProfileMemoryCandidate::getStatus, STATUS_PENDING)
                .gt(ProfileMemoryCandidate::getExpiresAt, now)))
                .stream()
                .filter(candidate -> Objects.equals(userId, candidate.getUserId()))
                .filter(candidate -> STATUS_PENDING.equals(candidate.getStatus()))
                .filter(candidate -> candidate.getExpiresAt() != null && candidate.getExpiresAt().isAfter(now))
                .filter(candidate -> normalizeKey(candidate.getProfileType()).equals(normalizeKey(profileType)))
                .filter(candidate -> normalizeKey(candidate.getSubject()).equals(normalizeKey(subject)))
                .filter(candidate -> normalizeKey(candidate.getContent()).equals(normalizeKey(content)))
                .filter(candidate -> normalizeKey(candidate.getDetail()).equals(normalizeKey(detail)))
                .findFirst()
                .orElse(null);
    }

    private ProfileValues findValue(Long userId, String item) {
        return safeRepositoryList(valuesRepository.selectList(new LambdaQueryWrapper<ProfileValues>()
                .eq(ProfileValues::getUserId, userId)
                .eq(ProfileValues::getActive, true)))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .filter(value -> normalizeKey(value.getItem()).equals(normalizeKey(item)))
                .findFirst()
                .orElse(null);
    }

    private ProfileEmotion findEmotion(Long userId, String emotion, String triggerDesc) {
        return safeRepositoryList(emotionRepository.selectList(new LambdaQueryWrapper<ProfileEmotion>()
                .eq(ProfileEmotion::getUserId, userId)
                .eq(ProfileEmotion::getActive, true)))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .filter(value -> normalizeKey(value.getEmotion()).equals(normalizeKey(emotion)))
                .filter(value -> normalizeKey(value.getTriggerDesc()).equals(normalizeKey(triggerDesc)))
                .findFirst()
                .orElse(null);
    }

    private ProfileRelationship findRelationship(Long userId, String name) {
        return safeRepositoryList(relationshipRepository.selectList(new LambdaQueryWrapper<ProfileRelationship>()
                .eq(ProfileRelationship::getUserId, userId)
                .eq(ProfileRelationship::getActive, true)))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .filter(value -> normalizeKey(value.getName()).equals(normalizeKey(name)))
                .findFirst()
                .orElse(null);
    }

    private ProfileFear findFear(Long userId, String type, String description) {
        return safeRepositoryList(fearRepository.selectList(new LambdaQueryWrapper<ProfileFear>()
                .eq(ProfileFear::getUserId, userId)
                .eq(ProfileFear::getActive, true)
                .eq(ProfileFear::getType, type)))
                .stream()
                .filter(value -> Objects.equals(userId, value.getUserId()))
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .filter(value -> normalizeKey(value.getType()).equals(normalizeKey(type)))
                .filter(value -> normalizeKey(value.getDescription()).equals(normalizeKey(description)))
                .findFirst()
                .orElse(null);
    }

    private ProfileMemoryCandidate loadPendingCandidate(Long userId, Long candidateId) {
        validateUser(userId);
        if (candidateId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
        ProfileMemoryCandidate candidate = candidateRepository
                .selectOne(new LambdaQueryWrapper<ProfileMemoryCandidate>()
                        .eq(ProfileMemoryCandidate::getId, candidateId)
                        .last("FOR UPDATE"));
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
            String reason,
            String gateVerdict) {
        writeAudit(userId, profileType, profileRecordId, candidateId, action,
                toAuditJson(before), toAuditJson(after), reason, gateVerdict);
    }

    private void writeAudit(
            Long userId,
            String profileType,
            Long profileRecordId,
            Long candidateId,
            String action,
            String beforeSnapshot,
            String afterSnapshot,
            String reason,
            String gateVerdict) {
        ProfileMemoryAuditLog auditLog = new ProfileMemoryAuditLog();
        auditLog.setUserId(userId);
        auditLog.setProfileType(profileType);
        auditLog.setProfileRecordId(profileRecordId);
        auditLog.setCandidateId(candidateId);
        auditLog.setAction(action);
        auditLog.setBeforeSnapshot(beforeSnapshot);
        auditLog.setAfterSnapshot(afterSnapshot);
        auditLog.setReason(reason);
        auditLog.setGateVerdict(gateVerdict);
        auditLog.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(auditLog);
    }

    /**
     * 记录一次门控拒绝（gate_reject）审计：门控裁判拒绝/降级了深层画像的直接写入，转候选确认。
     * 用于评估 PostureGate 裁判质量，shadow 模式下的放行结论随写入审计一并记录。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void recordGateRejection(Long userId, String profileType, String subject, String verdict) {
        validateUser(userId);
        writeAudit(userId, normalizeProfileType(profileType), null, null, "gate_reject",
                "{}", "{}", truncate(clean(subject), 500), truncate(clean(verdict), 20));
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

    private List<String> mergeEvidence(List<String> existing, List<String> incoming) {
        return Stream.concat(cleanList(existing).stream(), cleanList(incoming).stream())
                .distinct()
                .toList();
    }

    private BigDecimal max(BigDecimal first, BigDecimal second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) >= 0 ? first : second;
    }

    private String normalizeKey(String value) {
        return clean(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safeRepositoryList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String sceneSignal(String profileType, String subject, String content) {
        return "%s:%s:%s".formatted(profileType, subject.trim(), content.trim());
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        String cleaned = clean(value);
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
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
            Long sourceConversationId,
            String inputHash) {
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
            String userMessage,
            String gateVerdict) {
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
