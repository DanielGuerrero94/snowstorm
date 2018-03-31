package org.snomed.snowstorm.validation;

import com.google.common.collect.Sets;
import io.kaicode.elasticvc.api.VersionControlHelper;
import org.elasticsearch.index.query.QueryBuilder;
import org.ihtsdo.drools.RuleExecutor;
import org.ihtsdo.drools.response.InvalidContent;
import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.data.services.DescriptionService;
import org.snomed.snowstorm.core.data.services.QueryService;
import org.snomed.snowstorm.validation.domain.DroolsConcept;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Set;

@Service
public class DroolsValidationService {

	@Autowired
	private VersionControlHelper versionControlHelper;

	@Autowired
	private ElasticsearchOperations elasticsearchOperations;

	@Autowired
	private DescriptionService descriptionService;

	@Autowired
	private QueryService queryService;

	private RuleExecutor ruleExecutor;

	private String droolsRulesPath;

	public DroolsValidationService(@Value("${validation.drools.rules.path}") String droolsRulesPath) {
		this.droolsRulesPath = droolsRulesPath;
		newRuleExecutor();
	}

	public List<InvalidContent> validateConcept(String branchPath, Concept concept) {
		QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(branchPath);
		DroolsConcept droolsConcept = new DroolsConcept(concept);
		Set<String> ruleSetNames = Sets.newHashSet("common-authoring", "int-authoring"); // TODO: Pick up from branch metadata
		ConceptDroolsValidationService conceptService = new ConceptDroolsValidationService(branchPath, branchCriteria, elasticsearchOperations);
		DescriptionDroolsValidationService descriptionService = new DescriptionDroolsValidationService(branchPath, branchCriteria, versionControlHelper, elasticsearchOperations, this.descriptionService, queryService);
		RelationshipDroolsValidationService relationshipService = new RelationshipDroolsValidationService(branchCriteria, elasticsearchOperations);
		return ruleExecutor.execute(ruleSetNames, droolsConcept, conceptService, descriptionService, relationshipService, false, false);
	}

	public int reloadRules() {
		newRuleExecutor();
		return ruleExecutor.getTotalRulesLoaded();
	}

	private void newRuleExecutor() {
		Assert.notNull(droolsRulesPath, "Path to drools rules is required.");
		ruleExecutor = new RuleExecutor(droolsRulesPath);
	}
}
