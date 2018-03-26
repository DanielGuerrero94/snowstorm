package org.snomed.snowstorm.core.data.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kaicode.elasticvc.api.VersionControlHelper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.domain.Concepts;
import org.snomed.snowstorm.core.data.domain.Description;
import org.snomed.snowstorm.core.data.domain.QueryConcept;
import org.snomed.snowstorm.core.data.repositories.QueryConceptRepository;
import org.snomed.snowstorm.core.data.services.pojo.ResultMapPage;
import org.snomed.snowstorm.core.util.CollectionUtil;
import org.snomed.snowstorm.core.util.TimerUtil;
import org.snomed.snowstorm.ecl.ECLQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static io.kaicode.elasticvc.api.ComponentService.LARGE_PAGE;
import static java.lang.Long.parseLong;
import static org.elasticsearch.index.query.QueryBuilders.*;

@Service
public class QueryService {

	public static final PageRequest PAGE_OF_ONE = PageRequest.of(0, 1);

	@Autowired
	private ElasticsearchOperations elasticsearchTemplate;

	@Autowired
	private VersionControlHelper versionControlHelper;

	@Autowired
	private QueryConceptRepository queryConceptRepository;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private ECLQueryService eclQueryService;

	@Autowired
	private ReferenceSetMemberService memberService;

	@Autowired
	private RelationshipService relationshipService;

	@Autowired
	private ObjectMapper objectMapper;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public Page<ConceptMini> search(ConceptQueryBuilder conceptQuery, String branchPath, PageRequest pageRequest) {
		QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(branchPath);

		// Validate Lexical criteria
		String term = conceptQuery.getTermPrefix();
		boolean hasLexicalCriteria;
		if (term != null) {
			if (term.length() < 3) {
				return new PageImpl<>(Collections.emptyList());
			}
			hasLexicalCriteria = true;
		} else {
			hasLexicalCriteria = false;
		}
		boolean hasLogicalConditions = conceptQuery.hasLogicalConditions();

		Page<Long> conceptIdPage = null;
		if (hasLexicalCriteria && !hasLogicalConditions) {
			// Lexical Only
			logger.info("Lexical search {}", term);
			NativeSearchQuery query = getLexicalQuery(term, branchCriteria, pageRequest);
			final List<Long> pageOfIds = new LongArrayList();
			Page<Description> descriptionPage = elasticsearchTemplate.queryForPage(query, Description.class);
			descriptionPage.getContent().forEach(d -> pageOfIds.add(parseLong(d.getConceptId())));

			conceptIdPage = new PageImpl<>(pageOfIds, pageRequest, descriptionPage.getTotalElements());

		} else if (hasLogicalConditions && !hasLexicalCriteria) {
			// Logical Only

			Set<String> conceptIds = conceptQuery.getConceptIds();
			if (conceptIds != null && !conceptIds.isEmpty()) {
				// Concept ID pass-through
				List<Long> conceptIdList = conceptIds.stream().map(Long::parseLong).collect(Collectors.toList());
				List<Long> pageOfIds = CollectionUtil.subList(conceptIdList, pageRequest.getPageNumber(), pageRequest.getPageSize());
				conceptIdPage = new PageImpl<>(pageOfIds, pageRequest, conceptIdList.size());
			} else if (conceptQuery.getEcl() != null) {
				// ECL search
				Optional<Page<Long>> allConceptIdsOptional = doEclSearch(conceptQuery, branchPath, pageRequest, branchCriteria);
				conceptIdPage = allConceptIdsOptional.orElseGet(() ->
						getSimpleLogicalSearchPage(new ConceptQueryBuilder(conceptQuery.stated).selfOrDescendant(parseLong(Concepts.SNOMEDCT_ROOT)), branchCriteria, pageRequest));

			} else {
				// Primitive logical search
				conceptIdPage = getSimpleLogicalSearchPage(conceptQuery, branchCriteria, pageRequest);
			}

		} else if (hasLogicalConditions && hasLexicalCriteria) {
			// Logical and Lexical

			// Perform lexical search first because this probably the smaller set
			// Use term search for ordering and provide filter for logical search
			logger.info("Lexical search before logical {}", term);
			TimerUtil timer = new TimerUtil("Lexical and Logical Search");
			final List<Long> allLexicalMatchesWithOrdering = fetchAllLexicalMatches(branchCriteria, term);
			timer.checkpoint("lexical complete");

			// Fetch Logical matches
			// Have to fetch all logical matches and then create a page using the lexical ordering
			List<Long> allFilteredLogicalMatches;
			if (conceptQuery.getEcl() != null) {
				allFilteredLogicalMatches = doEclSearch(conceptQuery, branchPath, branchCriteria, allLexicalMatchesWithOrdering);
			} else {
				logger.info("Primitive Logical Search ");
				NativeSearchQueryBuilder logicalSearchQuery = new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(branchCriteria)
								.must(conceptQuery.getRootBuilder())
						)
						.withFilter(termsQuery(QueryConcept.CONCEPT_ID_FIELD, allLexicalMatchesWithOrdering))
						.withPageable(pageRequest);

				allFilteredLogicalMatches = new LongArrayList();
				try (CloseableIterator<QueryConcept> stream = elasticsearchTemplate.stream(logicalSearchQuery.build(), QueryConcept.class)) {
					stream.forEachRemaining(c -> allFilteredLogicalMatches.add(c.getConceptId()));
				}
			}
			timer.checkpoint("filtered logical complete");

			logger.info("{} lexical results, {} logical results", allLexicalMatchesWithOrdering.size(), allFilteredLogicalMatches.size());

			// Create page of ids which is an intersection of the lexical and logical lists using the lexical ordering
			conceptIdPage = CollectionUtil.listIntersection(allLexicalMatchesWithOrdering, allFilteredLogicalMatches, pageRequest);
		}

		if (conceptIdPage != null) {
			List<Long> pageOfConceptIds = conceptIdPage.getContent();
			ResultMapPage<String, ConceptMini> conceptMinis = conceptService.findConceptMinis(branchCriteria, pageOfConceptIds);
			return new PageImpl<>(getOrderedConceptList(pageOfConceptIds, conceptMinis.getResultsMap()), pageRequest, conceptIdPage.getTotalElements());
		} else {
			// No Criteria - return all concepts
			ResultMapPage<String, ConceptMini> conceptMinis = conceptService.findConceptMinis(branchCriteria, pageRequest);
			return new PageImpl<>(new ArrayList<>(conceptMinis.getResultsMap().values()), pageRequest, conceptMinis.getTotalElements());
		}
	}

	private Page<Long> getSimpleLogicalSearchPage(ConceptQueryBuilder conceptQuery, QueryBuilder branchCriteria, PageRequest pageRequest) {
		Page<Long> conceptIdPage;NativeSearchQueryBuilder logicalSearchQuery = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(conceptQuery.getRootBuilder())
				)
				.withPageable(pageRequest);
		Page<QueryConcept> pageOfConcepts = elasticsearchTemplate.queryForPage(logicalSearchQuery.build(), QueryConcept.class);

		List<Long> pageOfIds = pageOfConcepts.getContent().stream().map(QueryConcept::getConceptId).collect(Collectors.toList());
		conceptIdPage = new PageImpl<>(pageOfIds, pageRequest, pageOfConcepts.getTotalElements());
		return conceptIdPage;
	}

	private List<Long> fetchAllLexicalMatches(QueryBuilder branchCriteria, String term) {
		final List<Long> allLexicalMatchesWithOrdering = new LongArrayList();
		Page<Description> page;
		int pageNumber = 0;
		// Iterate through pages as stream does not seem to preserve ordering
		do {
			NativeSearchQuery query = getLexicalQuery(term, branchCriteria, PageRequest.of(pageNumber, LARGE_PAGE.getPageSize()));
			page = elasticsearchTemplate.queryForPage(query, Description.class);
			allLexicalMatchesWithOrdering.addAll(page.getContent().stream().map(d -> parseLong(d.getConceptId())).collect(Collectors.toList()));
			pageNumber++;
		} while (!page.isLast());
		return allLexicalMatchesWithOrdering;
	}

	private Optional<Page<Long>> doEclSearch(ConceptQueryBuilder conceptQuery, String branchPath, PageRequest pageRequest, QueryBuilder branchCriteria) {
		String ecl = conceptQuery.getEcl();
		logger.info("ECL Search {}", ecl);
		return eclQueryService.selectConceptIds(ecl, branchCriteria, branchPath, conceptQuery.isStated(), pageRequest);
	}

	private List<Long> doEclSearch(ConceptQueryBuilder conceptQuery, String branchPath, QueryBuilder branchCriteria, List<Long> conceptIdFilter) {
		String ecl = conceptQuery.getEcl();
		logger.info("ECL Search {}", ecl);
		Optional<Page<Long>> listOptional = eclQueryService.selectConceptIds(ecl, branchCriteria, branchPath, conceptQuery.isStated(), conceptIdFilter);
		return listOptional.map(Slice::getContent).orElse(conceptIdFilter);
	}

	private NativeSearchQuery getLexicalQuery(String term, QueryBuilder branchCriteria, PageRequest pageable) {
		BoolQueryBuilder lexicalQuery = boolQuery()
				.must(branchCriteria)
				.must(termQuery("active", true))
				.must(termQuery("typeId", Concepts.FSN));
		DescriptionService.addTermClauses(term, lexicalQuery);
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withQuery(lexicalQuery)
				.withPageable(pageable);
		NativeSearchQuery query = queryBuilder.build();
		DescriptionService.addTermSort(query);
		return query;
	}

	private List<ConceptMini> getOrderedConceptList(List<Long> termConceptIds, Map<String, ConceptMini> conceptMiniMap) {
		return termConceptIds.stream().filter(id -> conceptMiniMap.keySet().contains(id.toString())).map(id -> conceptMiniMap.get(id.toString())).collect(Collectors.toList());
	}

	public Page<QueryConcept> queryForPage(NativeSearchQuery searchQuery) {
		return elasticsearchTemplate.queryForPage(searchQuery, QueryConcept.class);
	}

	public CloseableIterator<QueryConcept> stream(NativeSearchQuery searchQuery) {
		return elasticsearchTemplate.stream(searchQuery, QueryConcept.class);
	}

	public Set<Long> retrieveAncestors(String conceptId, String path, boolean stated) {
		return retrieveAncestors(versionControlHelper.getBranchCriteria(path), path, stated, conceptId);
	}

	public Set<Long> retrieveParents(QueryBuilder branchCriteria, String path, boolean stated, String conceptId) {
		final NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(termQuery("conceptId", conceptId))
						.must(termQuery("stated", stated))
				)
				.withPageable(PAGE_OF_ONE)
				.build();
		List<QueryConcept> concepts = elasticsearchTemplate.queryForList(searchQuery, QueryConcept.class);
		return concepts.isEmpty() ? Collections.emptySet() : concepts.get(0).getParents();
	}

	public Set<Long> retrieveAncestors(QueryBuilder branchCriteria, String path, boolean stated, String conceptId) {
		final NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(termQuery("conceptId", conceptId))
						.must(termQuery("stated", stated))
				)
				.withPageable(LARGE_PAGE)
				.build();
		final List<QueryConcept> concepts = elasticsearchTemplate.queryForPage(searchQuery, QueryConcept.class).getContent();
		if (concepts.size() > 1) {
			logger.error("More than one index concept found {}", concepts);
			throw new IllegalStateException("More than one query-index-concept found for id " + conceptId + " on branch " + path + ".");
		}
		if (concepts.isEmpty()) {
			throw new IllegalArgumentException(String.format("Concept %s not found on branch %s", conceptId, path));
		}
		return concepts.get(0).getAncestors();
	}

	public Set<Long> retrieveAllAncestors(QueryBuilder branchCriteria, boolean stated, Collection<Long> conceptId) {
		final NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(termsQuery("conceptId", conceptId))
						.must(termQuery("stated", stated))
				)
				.withPageable(LARGE_PAGE)
				.build();
		final List<QueryConcept> concepts = elasticsearchTemplate.queryForPage(searchQuery, QueryConcept.class).getContent();
		Set<Long> allAncestors = new HashSet<>();
		for (QueryConcept concept : concepts) {
			allAncestors.addAll(concept.getAncestors());
		}
		return allAncestors;
	}
	
	public List<Long> retrieveAllDescendants(String conceptId, String path, boolean stated) {
		return retrieveAllDescendants(versionControlHelper.getBranchCriteria(path), stated, Collections.singleton(conceptId));
	}

	public Page<Long> retrieveDescendants(String conceptId, QueryBuilder branchCriteria, boolean stated, PageRequest pageRequest) {
		return doRetrieveDescendants(branchCriteria, stated, Collections.singleton(conceptId), pageRequest);
	}

	public List<Long> retrieveAllDescendants(QueryBuilder branchCriteria, boolean stated, Collection<? extends Object> conceptIds) {
		return doRetrieveDescendants(branchCriteria, stated, conceptIds, LARGE_PAGE).getContent();
	}

	private Page<Long> doRetrieveDescendants(QueryBuilder branchCriteria, boolean stated, Collection<? extends Object> conceptIds, PageRequest pageRequest) {
		final NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(termsQuery("ancestors", conceptIds))
						.must(termQuery("stated", stated))
				)
				.withPageable(pageRequest)
				.withSort(SortBuilders.fieldSort(QueryConcept.CONCEPT_ID_FIELD))// This could be anything
				.build();
		Page<QueryConcept> conceptsPage = elasticsearchTemplate.queryForPage(searchQuery, QueryConcept.class);
		List<Long> conceptIdsFound = conceptsPage.getContent().stream().map(QueryConcept::getConceptId).collect(Collectors.toList());
		return new PageImpl<>(conceptIdsFound, pageRequest, conceptsPage.getTotalElements());
	}

	public Set<Long> retrieveConceptsInReferenceSet(QueryBuilder branchCriteria, String referenceSetId) {
		return memberService.findConceptsInReferenceSet(branchCriteria, referenceSetId);
	}

	public Set<Long> retrieveRelationshipDestinations(Collection<Long> sourceConceptIds, List<Long> attributeTypeIds, QueryBuilder branchCriteria, boolean stated) {
		return relationshipService.retrieveRelationshipDestinations(sourceConceptIds, attributeTypeIds, branchCriteria, stated);
	}

	public void deleteAll() {
		queryConceptRepository.deleteAll();
	}

	/**
	 * Creates a ConceptQueryBuilder for use with search methods.
	 *
	 * @param stated If the stated or inferred form should be used in any logical conditions.
	 * @return a new ConceptQueryBuilder
	 */
	public ConceptQueryBuilder createQueryBuilder(boolean stated) {
		return new ConceptQueryBuilder(stated);
	}

	public ObjectMapper getObjectMapper() {
		return objectMapper;
	}

	public final class ConceptQueryBuilder {

		private final BoolQueryBuilder rootBuilder;
		private final BoolQueryBuilder logicalConditionBuilder;
		private final boolean stated;
		private String termPrefix;
		private String ecl;
		private Set<String> conceptIds;

		private ConceptQueryBuilder(boolean stated) {
			this.stated = stated;
			rootBuilder = boolQuery();
			logicalConditionBuilder = boolQuery();
			rootBuilder.must(termQuery("stated", stated));
			rootBuilder.must(logicalConditionBuilder);
		}

		public ConceptQueryBuilder self(Long conceptId) {
			logger.info("conceptId = {}", conceptId);
			logicalConditionBuilder.should(termQuery("conceptId", conceptId));
			return this;
		}

		public ConceptQueryBuilder descendant(Long conceptId) {
			logger.info("ancestors = {}", conceptId);
			logicalConditionBuilder.should(termQuery("ancestors", conceptId));
			return this;
		}

		public ConceptQueryBuilder selfOrDescendant(Long conceptId) {
			self(conceptId);
			descendant(conceptId);
			return this;
		}

		public ConceptQueryBuilder ecl(String ecl) {
			this.ecl = ecl;
			return this;
		}

		/**
		 * Term prefix has a minimum length of 3 characters.
		 *
		 * @param termPrefix
		 */
		public ConceptQueryBuilder termPrefix(String termPrefix) {
			this.termPrefix = termPrefix;
			return this;
		}

		private BoolQueryBuilder getRootBuilder() {
			return rootBuilder;
		}

		private String getTermPrefix() {
			return termPrefix;
		}

		public String getEcl() {
			return ecl;
		}

		public boolean isStated() {
			return stated;
		}

		private boolean hasLogicalConditions() {
			return getEcl() != null || logicalConditionBuilder.hasClauses() || (conceptIds != null && !conceptIds.isEmpty());
		}

		public void conceptIds(Set<String> conceptIds) {
			this.conceptIds = conceptIds;
		}

		public Set<String> getConceptIds() {
			return conceptIds;
		}
	}

}