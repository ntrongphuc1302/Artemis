package de.tum.cit.aet.artemis.iris.service.pyris;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_IRIS;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.cit.aet.artemis.iris.domain.settings.IrisSubSettingsType;
import de.tum.cit.aet.artemis.iris.dto.IngestionState;
import de.tum.cit.aet.artemis.iris.dto.IngestionStateResponseDTO;
import de.tum.cit.aet.artemis.iris.exception.IrisException;
import de.tum.cit.aet.artemis.iris.exception.IrisForbiddenException;
import de.tum.cit.aet.artemis.iris.exception.IrisInternalPyrisErrorException;
import de.tum.cit.aet.artemis.iris.service.pyris.dto.PyrisVariantDTO;
import de.tum.cit.aet.artemis.iris.service.pyris.dto.lectureingestionwebhook.PyrisWebhookLectureDeletionExecutionDTO;
import de.tum.cit.aet.artemis.iris.service.pyris.dto.lectureingestionwebhook.PyrisWebhookLectureIngestionExecutionDTO;
import de.tum.cit.aet.artemis.iris.service.pyris.job.IngestionWebhookJob;
import de.tum.cit.aet.artemis.iris.web.open.PublicPyrisStatusUpdateResource;

/**
 * This service connects to the Python implementation of Iris (called Pyris).
 * Pyris is responsible for executing the pipelines using (MM)LLMs and other tools asynchronously.
 * Status updates are sent to Artemis via {@link PublicPyrisStatusUpdateResource}
 */
@Service
@Profile(PROFILE_IRIS)
public class PyrisConnectorService {

    private static final Logger log = LoggerFactory.getLogger(PyrisConnectorService.class);

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    private final PyrisJobService pyrisJobService;

    @Value("${server.url}")
    private String artemisBaseUrl;

    @Value("${artemis.iris.url}")
    private String pyrisUrl;

    public PyrisConnectorService(@Qualifier("pyrisRestTemplate") RestTemplate restTemplate, MappingJackson2HttpMessageConverter springMvcJacksonConverter,
            PyrisJobService pyrisJobService) {
        this.restTemplate = restTemplate;
        this.objectMapper = springMvcJacksonConverter.getObjectMapper();
        this.pyrisJobService = pyrisJobService;
    }

    /**
     * Requests all available variants from Pyris for a feature
     *
     * @param feature The feature to get the variants for
     * @return A list of available Models as IrisVariantDTO
     */
    public List<PyrisVariantDTO> getOfferedVariants(IrisSubSettingsType feature) throws PyrisConnectorException {
        try {
            var response = restTemplate.getForEntity(pyrisUrl + "/api/v1/pipelines/" + feature.name() + "/variants", PyrisVariantDTO[].class);
            if (!response.getStatusCode().is2xxSuccessful() || !response.hasBody()) {
                throw new PyrisConnectorException("Could not fetch offered models");
            }
            return Arrays.asList(response.getBody());
        }
        catch (HttpStatusCodeException e) {
            log.error("Failed to fetch offered models from Pyris", e);
            throw new PyrisConnectorException("Could not fetch offered models");
        }
    }

    /**
     * Executes a pipeline with the given feature and variant
     *
     * @param feature      The feature name of the pipeline to execute
     * @param variant      The variant of the feature to execute
     * @param executionDTO The DTO sent as a body for the execution
     */
    public void executePipeline(String feature, String variant, Object executionDTO) {
        var endpoint = "/api/v1/pipelines/" + feature + "/" + variant + "/run";
        try {
            restTemplate.postForEntity(pyrisUrl + endpoint, objectMapper.valueToTree(executionDTO), Void.class);
        }
        catch (HttpStatusCodeException e) {
            throw toIrisException(e);
        }
        catch (RestClientException | IllegalArgumentException e) {
            log.error("Failed to send request to Pyris", e);
            throw new PyrisConnectorException("Could not fetch response from Iris");
        }
    }

    /**
     * Executes a webhook and send lectures to the webhook with the given variant
     *
     * @param variant      The variant of the feature to execute
     * @param executionDTO The DTO sent as a body for the execution
     */
    public void executeLectureAddtionWebhook(String variant, PyrisWebhookLectureIngestionExecutionDTO executionDTO) {
        var endpoint = "/api/v1/webhooks/lectures/" + variant;
        try {
            restTemplate.postForEntity(pyrisUrl + endpoint, objectMapper.valueToTree(executionDTO), Void.class);
        }
        catch (HttpStatusCodeException e) {
            log.error("Failed to send lecture unit {} to Pyris: {}", executionDTO.pyrisLectureUnit().lectureUnitId(), e.getMessage());
            throw toIrisException(e);
        }
        catch (RestClientException | IllegalArgumentException e) {
            log.error("Failed to send lecture unit {} to Pyris: {}", executionDTO.pyrisLectureUnit().lectureUnitId(), e.getMessage());
            throw new PyrisConnectorException("Could not fetch response from Pyris");
        }
    }

    /**
     * Retrieves the ingestion state of the lecture unit specified by retrieving the ingestion state from the vector database in Pyris.
     *
     * @param courseId      id of the course
     * @param lectureId     id of the lecture
     * @param lectureUnitId id of the lectureUnit to check in the Pyris vector database
     * @return The ingestion state of the lecture Unit
     *
     */
    IngestionState getLectureUnitIngestionState(long courseId, long lectureId, long lectureUnitId) {
        try {
            String encodedBaseUrl = URLEncoder.encode(artemisBaseUrl, StandardCharsets.UTF_8);
            String url = pyrisUrl + "/api/v1/courses/" + courseId + "/lectures/" + lectureId + "/lectureUnits/" + lectureUnitId + "/ingestion-state?base_url=" + encodedBaseUrl;
            IngestionStateResponseDTO response = restTemplate.getForObject(url, IngestionStateResponseDTO.class);
            IngestionState state = response.state();
            if (state != IngestionState.DONE) {
                if (pyrisJobService.currentJobs().stream().filter(job -> job instanceof IngestionWebhookJob).map(job -> (IngestionWebhookJob) job)
                        .anyMatch(ingestionJob -> ingestionJob.courseId() == courseId && ingestionJob.lectureId() == lectureId && ingestionJob.lectureUnitId() == lectureUnitId)) {
                    return IngestionState.IN_PROGRESS;
                }
            }
            return state;
        }
        catch (RestClientException | IllegalArgumentException e) {
            log.error("Error fetching ingestion state for lecture {}, lecture unit {}", lectureId, lectureUnitId, e);
            throw new PyrisConnectorException("Error fetching ingestion state for lecture unit" + lectureUnitId);
        }
    }

    /**
     * Executes a webhook and send lectures to the webhook with the given variant
     *
     * @param executionDTO The DTO sent as a body for the execution
     */
    public void executeLectureDeletionWebhook(PyrisWebhookLectureDeletionExecutionDTO executionDTO) {
        var endpoint = "/api/v1/webhooks/lectures/delete";
        try {
            restTemplate.postForEntity(pyrisUrl + endpoint, objectMapper.valueToTree(executionDTO), Void.class);
        }
        catch (HttpStatusCodeException e) {
            log.error("Failed to send lectures to Pyris", e);
            throw toIrisException(e);
        }
        catch (RestClientException | IllegalArgumentException e) {
            log.error("Failed to send lectures to Pyris", e);
            throw new PyrisConnectorException("Could not fetch response from Pyris");
        }
    }

    private IrisException toIrisException(HttpStatusCodeException e) {
        return switch (e.getStatusCode().value()) {
            case 401, 403 -> new IrisForbiddenException();
            case 400, 500 -> new IrisInternalPyrisErrorException(tryExtractErrorMessage(e));
            default -> new IrisInternalPyrisErrorException(e.getMessage());
        };
    }

    private String tryExtractErrorMessage(HttpStatusCodeException ex) {
        try {
            return objectMapper.readTree(ex.getResponseBodyAsString()).required("detail").required("errorMessage").asText();
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Failed to parse error message from Pyris", e);
            return "";
        }
    }
}
