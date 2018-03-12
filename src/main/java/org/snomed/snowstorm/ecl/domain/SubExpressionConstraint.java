package org.snomed.snowstorm.ecl.domain;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.snomed.snowstorm.core.data.domain.QueryConcept;
import org.snomed.snowstorm.core.data.services.QueryService;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.elasticsearch.index.query.QueryBuilders.*;

public class SubExpressionConstraint extends ExpressionConstraint {

	private final Operator operator;
	private String conceptId;
	private boolean wildcard;
	private ExpressionConstraint nestedExpressionConstraint;

	public SubExpressionConstraint(Operator operator) {
		this.operator = operator;
	}

	@Override
	public boolean isWildcard() {
		return wildcard && Operator.memberOf != operator;
	}

	@Override
	public void addCriteria(BoolQueryBuilder query, String path, QueryBuilder branchCriteria, boolean stated, QueryService queryService) {
		if (conceptId != null) {
			if (operator != null) {
				applyConceptCriteriaWithOperator(conceptId, operator, query, path, branchCriteria, stated, queryService);
			} else {
				query.must(QueryBuilders.termQuery(QueryConcept.CONCEPT_ID_FIELD, conceptId));
			}
		} else if (nestedExpressionConstraint != null) {
			Optional<List<Long>> conceptIdsOptional = nestedExpressionConstraint.select(path, branchCriteria, stated, null, queryService);
			if (!conceptIdsOptional.isPresent()) {
				return;
			}
			List<Long> conceptIds = conceptIdsOptional.get();
			if (!conceptIds.isEmpty()) {
				conceptIds.add(ExpressionConstraint.MISSING_LONG);
			}
			BoolQueryBuilder filterQuery = boolQuery();
			query.filter(filterQuery);
			if (operator != null) {
				for (Long conceptId : conceptIds) {
					applyConceptCriteriaWithOperator(conceptId.toString(), operator, filterQuery, path, branchCriteria, stated, queryService);
				}
			} else {
				filterQuery.must(termsQuery(QueryConcept.CONCEPT_ID_FIELD, conceptIds));
			}
		} else if (operator == Operator.memberOf) {
			// Member of any reference set
			query.must(termsQuery(QueryConcept.CONCEPT_ID_FIELD, queryService.retrieveConceptsInReferenceSet(branchCriteria, null)));
		}
		// Else Wildcard! which has no constraints
	}

	@Override
	public Optional<List<Long>> select(String path, QueryBuilder branchCriteria, boolean stated, Collection<Long> conceptIdFilter, QueryService queryService) {
		if (isWildcard()) {
			return Optional.empty();
		}
		return super.select(path, branchCriteria, stated, conceptIdFilter, queryService);
	}

	private void applyConceptCriteriaWithOperator(String conceptId, Operator operator, BoolQueryBuilder query, String path, QueryBuilder branchCriteria, boolean stated, QueryService queryService) {
		switch (operator) {
			case childof:
				query.must(termQuery(QueryConcept.PARENTS_FIELD, conceptId));
				break;
			case descendantorselfof:
				// <<
				query.must(
						boolQuery()
								.should(termQuery(QueryConcept.ANCESTORS_FIELD, conceptId))
								.should(termQuery(QueryConcept.CONCEPT_ID_FIELD, conceptId))
				);
				break;
			case descendantof:
				// <
				query.must(termQuery(QueryConcept.ANCESTORS_FIELD, conceptId));
				break;
			case parentof:
				Set<Long> parents = queryService.retrieveParents(branchCriteria, path, stated, conceptId);
				query.must(termsQuery(QueryConcept.CONCEPT_ID_FIELD, parents));
				break;
			case ancestororselfof:
				query.must(
						boolQuery()
								.should(termsQuery(QueryConcept.CONCEPT_ID_FIELD, queryService.retrieveAncestors(branchCriteria, path, stated, conceptId)))
								.should(termQuery(QueryConcept.CONCEPT_ID_FIELD, conceptId))
				);
				break;
			case ancestorof:
				// > x
				query.must(termsQuery(QueryConcept.CONCEPT_ID_FIELD, queryService.retrieveAncestors(branchCriteria, path, stated, conceptId)));
				break;
			case memberOf:
				// ^
				query.must(termsQuery(QueryConcept.CONCEPT_ID_FIELD, queryService.retrieveConceptsInReferenceSet(branchCriteria, conceptId)));
				break;
		}
	}

	public void wildcard() {
		this.wildcard = true;
	}

	public void setConceptId(String conceptId) {
		this.conceptId = conceptId;
	}

	public String getConceptId() {
		return conceptId;
	}

	public void setNestedExpressionConstraint(ExpressionConstraint nestedExpressionConstraint) {
		this.nestedExpressionConstraint = nestedExpressionConstraint;
	}

	@Override
	public String toString() {
		return "SubExpressionConstraint{" +
				"operator=" + operator +
				", conceptId='" + conceptId + '\'' +
				", wildcard=" + wildcard +
				", nestedExpressionConstraint=" + nestedExpressionConstraint +
				'}';
	}
}
