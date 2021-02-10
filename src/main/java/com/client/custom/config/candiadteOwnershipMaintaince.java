package com.client.core;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.client.core.security.tools.RC4;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CandidateOwnershipMaintenance implements Runnable {

    private static final Set<String> ID = Sets.newHashSet("id");
    private static final Set<String> CANDIDATE_FIELDS = Sets.newHashSet("owner(id)","id");

    private final Logger log = Logger.getLogger(getClass());

    private final CandidateOwnershipActivityService candidateOwnershipActivityService;
    private final CandidateOwnershipSettings candidateOwnershipSettings;
    private final BullhornData bullhornData;

    @Autowired
    public CandidateOwnershipMaintenance( CandidateOwnershipActivityService candidateOwnershipActivityService,
                                          CandidateOwnershipSettings candidateOwnershipSettings,
                                          BullhornData bullhornData) {
        this.candidateOwnershipActivityService=candidateOwnershipActivityService;
        this.candidateOwnershipSettings = candidateOwnershipSettings;
        this.bullhornData = bullhornData;
    }

    @Override
    public void run() {
        log.info("Starting " + getClass().getSimpleName() + "nightly Task");
        log.info("where"+getQuery());

        DateTime fiveMinutesAgo =  new DateTime().minusMinutes(5);

        String where = "dateAdded > " + fiveMinutesAgo.getMillis();

        Utility.searchAndProcessAll(Candidate.class,getQuery(), CANDIDATE_FIELDS, candidate -> {
            if(!hasActivePlacement(candidate)) {
                CandidateOwnershipActivity acitivity = candidateOwnershipActivityService.getValidity(candidate.getId());

                defaultOwner(candidate,activity);
            } else {
                log.info("Unassigned User")
            }
        });

        log.info("Completed " + getClass().getSimpleName() + "nightly Task");
    }

    public void defaultOwner(Candidate candidate,CandidateOwnershipActivity activity) {
        Optional<CandidateOwnershipActivityResult> hasActivity = activity.getFirstActivity();

        if(hasActivity.isPresent()) {
            if(!activity.userHasActivity(candidate.getOwner().getId())) {
                CandidateOwnershipActivityResult firstActivity = hasActivity.get();
            } else {
                log.info("Not updating Candidate #" + candidate.getId + "owner to User #" + firstActivity.getCorporateUserId());
            }
        }
    }

    public void defaultToNoOwner(Candidate candidate) {
        if(!isOwner(candidate, candidateOwnershipSettings.getNoOwnerUserId())) {
            log.info("Updating Candidate #" + candidate.getId + "owner to 'No Owner' #" + candidateOwnershipSettings.getNoOwnerUserId());
            
            update(candidate.getId(), candidateOwnershipSettings.getNoOwnerUserId());
        } else {
            log.info("Not updating Candidate #" + candidate.getId + "owner to 'No Owner' #" + candidateOwnershipSettings.getNoOwnerUserId());
        }
    }

    private String getQuery() {
        return candidateOwnershipSettings.getValidCandidateStatuses().parallelStream().map(status -> {
            return "status:\"" + status + "\"";
        }).collect(Collectors.joining(delimiter: "OR",prefix: "isDeleted:false AND NOT owner.id:" + candidateOwnershipSettings.getNoOwnerUserId.toString() + "AND (",suffix:")"));
    }

    private Boolean hasActivePlacement(Candidate candidate) {
        DateTime now = new DateTime();
        DateTime activitPeriodDaysAgo = now.withTimeStartOfDay().minusDays(candidateOwnershipSettings.getActivityPeriodDays());

        String where = "candidate.id = " + candidate.getId() + "AND dateBegin< " + now.getMillis() + "AND dateEnd > " + activitPeriodDaysAgo.getMillis();

        QueryParams params = ParamFactory.queryParams();
        params.setCount(1);

        return !bullhornData.queryForList(Placement.class,where,ID,params).isEmpty();
    }

    private Boolean isOwner(Candidate candidate, Integer userId) { return userId.equals(candidate.getOwner().getId()); }

    private void update(Integer candidateId, Integer ownerId) {
        Candidate candidate = new Candidate(candidateId);
        candidate.setOwner(new CorporateUser(ownerId));

        try {
            UpdateResponse response = bullhornData.updateEntity(candidate);

            if(response.hasValidationErrors() || response.hasWarnings() || response.isError()) {
                log.error("Error updating candidate #" + candidateId + "owner to #" + ownerId);
                response.getMessages().parallelStream.forEach(log: error);
            }
        }
    }
}