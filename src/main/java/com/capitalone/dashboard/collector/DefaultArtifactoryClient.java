package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.client.RestClient;
import com.capitalone.dashboard.model.ArtifactItem;
import com.capitalone.dashboard.model.ArtifactoryRepo;
import com.capitalone.dashboard.model.BaseArtifact;
import com.capitalone.dashboard.model.BinaryArtifact;
import com.capitalone.dashboard.model.ServerSetting;
import com.capitalone.dashboard.repository.BinaryArtifactRepository;
import com.capitalone.dashboard.util.ArtifactUtil;
import com.capitalone.dashboard.util.Supplier;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class DefaultArtifactoryClient implements ArtifactoryClient {
	public static final int UPPER_INDEX = -1;
	private static final Logger LOGGER = LoggerFactory.getLogger(DefaultArtifactoryClient.class);

	private static final String REPOS_URL_SUFFIX = "api/repositories";
	private static final String AQL_URL_SUFFIX = "api/search/aql";

	private final DateFormat FULL_DATE = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX");

	private final ArtifactorySettings artifactorySettings;
	private final RestClient restClient;

	private final List<Pattern> artifactPatterns;

	private final BinaryArtifactRepository binaryArtifactRepository;

	@Autowired
	public DefaultArtifactoryClient(ArtifactorySettings artifactorySettings, RestClient restClient, BinaryArtifactRepository binaryArtifactRepository) {
		this.artifactorySettings = artifactorySettings;
		this.restClient = restClient;
		this.binaryArtifactRepository = binaryArtifactRepository;
		this.artifactPatterns = new ArrayList<>();

		if (artifactorySettings.getServers() != null) {
			for (String str : getPatterns()) {
				try {
					Pattern p = Pattern.compile(str);

					LOGGER.info("Adding Pattern " + p.pattern());

					artifactPatterns.add(p);
				} catch (PatternSyntaxException e) {
					LOGGER.error("Invalid pattern: " + e.getMessage());
					throw e;
				}
			}
		}

		if (artifactPatterns.isEmpty()) {
			throw new IllegalStateException("No valid artifact patterns configured. Aborting.");
		}
	}

	private List<String> getPatterns(){
		List<String> patterns = new ArrayList<>();
		if(artifactorySettings.getServers()!=null) {
			artifactorySettings.getServers().forEach(serverSetting ->
					serverSetting.getRepoAndPatterns().forEach(repoAndPattern -> patterns.addAll(repoAndPattern.getPatterns())));
		}
		return patterns;
	}

	public List<ArtifactoryRepo> getRepos(String instanceUrl) {
		List<ArtifactoryRepo> result = new ArrayList<>();
		ResponseEntity<String> responseEntity = makeRestCall(instanceUrl, REPOS_URL_SUFFIX);
		String returnJSON = responseEntity.getBody();
		JSONParser parser = new JSONParser();

		try {
			JSONArray jsonRepos = (JSONArray) parser.parse(returnJSON);

			for (Object repo : jsonRepos) {
				JSONObject jsonRepo = (JSONObject) repo;

				final String repoName = getString(jsonRepo, "key");
				final String repoURL = getString(jsonRepo, "url");
				LOGGER.debug("repoName:" + repoName);
				LOGGER.debug("repoURL: " + repoURL);
				ArtifactoryRepo artifactoryRepo = new ArtifactoryRepo();
				artifactoryRepo.setInstanceUrl(instanceUrl);
				artifactoryRepo.setRepoName(repoName);
				artifactoryRepo.setRepoUrl(repoURL);

				// add the repo
				result.add(artifactoryRepo);
			}
		} catch (ParseException e) {
			LOGGER.error("Parsing repos on instance: " + instanceUrl, e);
		}

		return result;
	}

	public List<BaseArtifact> getArtifactItems(String instanceUrl, String repoName,String pattern, long lastUpdated) {
		List<BaseArtifact> baseArtifacts = new ArrayList<>();
		if (StringUtils.isNotEmpty(instanceUrl) && StringUtils.isNotEmpty(repoName)) {
			long currentTime = System.currentTimeMillis();
			// time's worth of data - 1 day
			long timeInterval = TimeUnit.DAYS.toMillis(1);
			// calculate # of iterations/calls needed (# of days since last collector update - rounded up)
			long numTimesToCollect = (long) Math.ceil((double) (currentTime - lastUpdated) / (double) timeInterval);
			long lastUpdatedOffset = lastUpdated;

			for (int i = 0; i < numTimesToCollect; i++) {
				String body = "items.find({\"created\" : {\"$gt\" : \"" + FULL_DATE.format(new Date(lastUpdatedOffset))
						+ "\"}, \"created\" : {\"$lte\" : \"" + FULL_DATE.format(new Date(Math.min(lastUpdatedOffset + timeInterval, System.currentTimeMillis())))
						+ "\"},\"repo\":{\"$eq\":\"" + repoName
						+ "\"}}).include(\"*\")";

				lastUpdatedOffset += timeInterval;

				ResponseEntity<String> responseEntity = makeRestPost(instanceUrl, AQL_URL_SUFFIX, MediaType.TEXT_PLAIN, body);
				String returnJSON = responseEntity.getBody();
				JSONParser parser = new JSONParser();
				try {
					JSONObject json = (JSONObject) parser.parse(returnJSON);
					JSONArray jsonArtifacts = getJsonArray(json, "results");
					LOGGER.info("Total JSON Artifacts -- " + jsonArtifacts.size());
					int count =0;
					for (Object artifact : jsonArtifacts) {
						JSONObject jsonArtifact = (JSONObject) artifact;
						BaseArtifact baseArtifact = new BaseArtifact();

						String repo = getString(jsonArtifact, "repo");
						final String artifactCanonicalName = getString(jsonArtifact, "name");
						String artifactPath = getString(jsonArtifact, "path");
						String fullPath = artifactPath + "/" + artifactCanonicalName;

						Pattern p = Pattern.compile(pattern);
						BinaryArtifact result = ArtifactUtil.parse(p, fullPath);
						String artName = "";
						String artPath = artifactPath;
						if (result != null) {
							artName = result.getArtifactName();
							artPath = result.getArtifactGroupId() + "/" + result.getArtifactName();
						}


						if (artifactPath.charAt(artifactPath.length() - 1) == '/') {
							artifactPath = artifactPath.substring(0, artifactPath.length() - 1);
						}

						// create artifact_items (collector_item)
						ArtifactItem artifactItem = createArtifactItem(instanceUrl, repo, artName, artPath);

						String sTimestamp = getString(jsonArtifact, "modified");
						if (sTimestamp == null) {
							sTimestamp = getString(jsonArtifact, "created");
						}
						long timestamp = 0;
						if (sTimestamp != null) {
							try {
								Date date = FULL_DATE.parse(sTimestamp);
								timestamp = date.getTime();
							} catch (java.text.ParseException e) {
								LOGGER.error("Parsing artifact timestamp: " + sTimestamp, e);
							}
						}

						// find existing base artifact matching artifact item unique options
						BaseArtifact suspect = baseArtifacts.stream().filter(b -> b.getArtifactItem().getArtifactName().equalsIgnoreCase(artifactItem.getArtifactName()) && b.getArtifactItem().getRepoName().equalsIgnoreCase(artifactItem.getRepoName())
								&& b.getArtifactItem().getPath().equalsIgnoreCase(artifactItem.getPath())).findFirst().orElse(baseArtifact);
						// create artifactInfo
						List<BinaryArtifact> bas = createArtifactForArtifactBased(artifactCanonicalName, artifactPath, timestamp, jsonArtifact);
						if (CollectionUtils.isNotEmpty(bas)) {
							insertOrUpdateBaseArtifact(baseArtifacts, artifactItem, suspect, bas);
						}
						count++;
						LOGGER.info("artifact count -- " + count + "  artifactPath-- " + artifactPath);
					}
				} catch (ParseException e) {
					LOGGER.error("Parsing artifact items on instance: " + instanceUrl + " and repo: " + repoName, e);
				}
			}
		}
		return baseArtifacts;
	}

	private void insertOrUpdateBaseArtifact(List<BaseArtifact> baseArtifacts, ArtifactItem artifactItem, BaseArtifact suspect, List<BinaryArtifact> bas) {
		for (BinaryArtifact ba: bas) {
			if(containsBinaryArtifactWithBuildInfo(suspect, ba)){
				if(baseArtifactNotNull(baseArtifacts, suspect)){
					addOrUpdateBinaryArtifactToBaseArtifact(baseArtifacts, artifactItem, suspect, ba);
				}else{
					addNewBaseArtifact(baseArtifacts, artifactItem, suspect, ba);
				}
			}

		}
	}

	private void addNewBaseArtifact(List<BaseArtifact> baseArtifacts, ArtifactItem artifactItem, BaseArtifact suspect, BinaryArtifact ba) {
		suspect.getBinaryArtifacts().add(ba);
		suspect.setArtifactItem(artifactItem);
		baseArtifacts.add(suspect);
	}

	private void addOrUpdateBinaryArtifactToBaseArtifact(List<BaseArtifact> baseArtifacts, ArtifactItem artifactItem, BaseArtifact suspect, BinaryArtifact ba) {
		int index = baseArtifacts.indexOf(suspect);
		if (index > UPPER_INDEX) {
			updateArtifactsInExistingBaseArtifact(baseArtifacts, ba, index);
		} else {
			addNewBaseArtifact(baseArtifacts,artifactItem,suspect,ba);
		}
	}

	private void updateArtifactsInExistingBaseArtifact(List<BaseArtifact> baseArtifacts, BinaryArtifact ba, int index) {
		int ind = baseArtifacts.get(index).getBinaryArtifacts().indexOf(ba);
		if(ind > UPPER_INDEX){
			baseArtifacts.get(index).getBinaryArtifacts().set(ind,ba);
		}else{
			baseArtifacts.get(index).getBinaryArtifacts().add(ba);
		}
	}

	private boolean baseArtifactNotNull(List<BaseArtifact> baseArtifacts, BaseArtifact suspect) {
		return baseArtifacts!= null && suspect.getArtifactItem()!=null && !baseArtifacts.isEmpty();
	}

	private boolean containsBinaryArtifactWithBuildInfo(BaseArtifact suspect, BinaryArtifact ba) {
		return isNewData(suspect, ba) || artifactWithBuildData(suspect, ba);
	}

	private boolean artifactWithBuildData(BaseArtifact suspect, BinaryArtifact ba) {
		return suspect.getBinaryArtifacts().contains(ba) && CollectionUtils.isNotEmpty(ba.getBuildInfos());
	}

	private boolean isNewData(BaseArtifact suspect, BinaryArtifact ba) {
		return !suspect.getBinaryArtifacts().contains(ba);
	}


	public List<BinaryArtifact> getArtifacts(String instanceUrl, String repoName, long lastUpdated) {
		List<BinaryArtifact> result = new ArrayList<>();
		// get the list of artifacts
		if (StringUtils.isNotEmpty(instanceUrl) && StringUtils.isNotEmpty(repoName)) {
			String body = "items.find({\"created\" : {\"$gt\" : \"" + FULL_DATE.format(new Date(lastUpdated))
					+ "\"},\"repo\":{\"$eq\":\"" + repoName
					+ "\"}}).include(\"repo\", \"name\", \"path\", \"created\", \"modified\", \"property\")";

			ResponseEntity<String> responseEntity = makeRestPost(instanceUrl, AQL_URL_SUFFIX, MediaType.TEXT_PLAIN, body);
			String returnJSON = responseEntity.getBody();
			JSONParser parser = new JSONParser();

			try {
				JSONObject json = (JSONObject) parser.parse(returnJSON);
				JSONArray jsonArtifacts = getJsonArray(json, "results");
				for (Object artifact : jsonArtifacts) {
					JSONObject jsonArtifact = (JSONObject) artifact;

					final String artifactCanonicalName = getString(jsonArtifact, "name");
					String artifactPath = getString(jsonArtifact, "path");
					if (artifactPath.charAt(artifactPath.length()-1) == '/') {
						artifactPath = artifactPath.substring(0, artifactPath.length()-1);
					}
					String sTimestamp = getString(jsonArtifact, "modified");
					if (sTimestamp == null) {
						sTimestamp = getString(jsonArtifact, "created");
					}
					long timestamp = 0;
					if (sTimestamp != null) {
						try {
							Date date = FULL_DATE.parse(sTimestamp);
							timestamp = date.getTime();
						} catch (java.text.ParseException e) {
							LOGGER.error("Parsing artifact timestamp: " + sTimestamp, e);
						}
					}
					BinaryArtifact ba = createArtifact(artifactCanonicalName, artifactPath, timestamp, jsonArtifact);
					if (ba != null) {
						result.add(ba);
					}
				}
			} catch (ParseException e) {
				LOGGER.error("Parsing artifacts on instance: " + instanceUrl + " and repo: " + repoName, e);
			}
		}

		return result;
	}


	/**
	 * Creates an artifact given its canonical name and path.
	 * Artifacts are created by supplied pattern configurations. By default three are supplied:
	 * 1. Maven artifacts:
	 * 		[org]/[module]/[version]/[module]-[version]([-classifier])(.[ext])
	 * 2. Ivy artifacts:
	 * 		(a) [org]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])
	 * 		(b) [org]/[module]/[revision]/ivy-[revision](-[classifier]).xml
	 *
	 * Using these patterns, we extract the artifact name, version and group id from the canonical name and path.
	 *
	 * @param artifactCanonicalName			artifact's canonical name in artifactory
	 * @param artifactPath					artifact's path in artifactory
	 * @param timestamp						the artifact's timestamp
	 * @param jsonArtifact 					the artifact metadata is extracted from here
	 * @return
	 */
	private BinaryArtifact createArtifact(String artifactCanonicalName, String artifactPath, long timestamp, JSONObject jsonArtifact) {
		BinaryArtifact result = null;
		String fullPath = artifactPath + "/" + artifactCanonicalName;

		int idx = 0;
		for (Pattern pattern : artifactPatterns) {
			result = ArtifactUtil.parse(pattern, fullPath);

			if (result != null) {
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Artifact at " + fullPath + " matched pattern " + idx);
				}

				result.setType(getString(jsonArtifact, "type"));
				result.setCreatedTimeStamp(convertTimestamp(getString(jsonArtifact, "created")));
				result.setCreatedBy(getString(jsonArtifact, "created_by"));
				result.setModifiedTimeStamp(convertTimestamp(getString(jsonArtifact, "modified")));
				result.setModifiedBy(getString(jsonArtifact, "modified_by"));
				result.setActual_md5(getString(jsonArtifact,  "actual_md5"));
				result.setActual_sha1(getString(jsonArtifact, "actual_sha1"));
				result.setCanonicalName(artifactCanonicalName);
				result.setTimestamp(timestamp);
				result.setVirtualRepos(getJsonArray(jsonArtifact, "virtual_repos"));
				addMetadataToArtifact(result, jsonArtifact);


				return result;
			}

			idx++;
		}

		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("Artifact at " + fullPath + " did not match any patterns.");
		}
		return null;
	}

	private List<BinaryArtifact> createArtifactForArtifactBased(String artifactCanonicalName, String artifactPath, long timestamp, JSONObject jsonArtifact) {
		BinaryArtifact result = null;
		String fullPath = artifactPath + "/" + artifactCanonicalName;
		List<BinaryArtifact> binaryArtifactList = new ArrayList<>();
		int idx = 0;
		for (Pattern pattern : artifactPatterns) {
         result = ArtifactUtil.parse(pattern, fullPath);
            if (result != null) {
                String artifactName = result.getArtifactName();
                String artifactVersion = result.getArtifactVersion();
                Iterable<BinaryArtifact> bas = binaryArtifactRepository.findByArtifactNameAndArtifactVersion(artifactName, artifactVersion);
                if(!IterableUtils.isEmpty(bas)){
				   for (BinaryArtifact ba: bas) {
					   setCollectorItemId(result, ba);
					   setBuilds(result, ba);
					   binaryArtifactRepository.delete(ba.getId());
				   }
			   	}
				result.setType(getString(jsonArtifact, "type"));
				result.setCreatedTimeStamp(convertTimestamp(getString(jsonArtifact, "created")));
				result.setCreatedBy(getString(jsonArtifact, "created_by"));
				result.setModifiedTimeStamp(convertTimestamp(getString(jsonArtifact, "modified")));
				result.setModifiedBy(getString(jsonArtifact, "modified_by"));
				result.setActual_md5(getString(jsonArtifact, "actual_md5"));
				result.setActual_sha1(getString(jsonArtifact, "actual_sha1"));
				result.setCanonicalName(artifactCanonicalName);
				result.setTimestamp(timestamp);
				result.setVirtualRepos(getJsonArray(jsonArtifact, "virtual_repos"));
				addMetadataToArtifact(result, jsonArtifact);

				binaryArtifactList.add(result);
				if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Artifact at " + fullPath + " matched pattern " + idx);
                }
            }
			idx++;
		}

		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("Artifact at " + fullPath + " did not match any patterns.");
		}
		return binaryArtifactList;
	}

	private void setBuilds(BinaryArtifact result, BinaryArtifact ba) {
		if(Objects.nonNull(ba.getBuildInfos())&& !ba.getBuildInfos().isEmpty()){
			result.setBuildInfos(ba.getBuildInfos());
		}
	}

	private void setCollectorItemId(BinaryArtifact result, BinaryArtifact ba) {
		if(ba.getCollectorItemId()!=null){
			result.setCollectorItemId(ba.getCollectorItemId());
		}
	}


	private long convertTimestamp(String sTimestamp){
		long timestamp = 0;
		if (sTimestamp != null) {
			try {
				Date date = FULL_DATE.parse(sTimestamp);
				timestamp = date.getTime();
			} catch (java.text.ParseException e) {
				LOGGER.error("Parsing artifact timestamp: " + sTimestamp, e);
			}
		}
		return timestamp;
	}
	@SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
	private void addMetadataToArtifact(BinaryArtifact ba, JSONObject jsonArtifact) {
		if (ba != null && jsonArtifact != null) {
			JSONArray jsonProperties = getJsonArray(jsonArtifact, "properties");
			for (Object property : jsonProperties) {
				JSONObject jsonProperty = (JSONObject) property;
				String key = getString(jsonProperty, "key");
				String value = getString(jsonProperty, "value");
				switch (key) {
					case "build.url":
					case "build_url":
					case "buildUrl":
						ba.setBuildUrl(value);
						break;
					case "build.number":
					case "build_number":
					case "buildNumber":
						ba.setBuildNumber(value);
						break;
					case "job.url":
					case "job_url":
					case "jobUrl":
						ba.setJobUrl(value);
						break;
					case "job.name":
					case "job_name":
					case "jobName":
						ba.setJobName(value);
						break;
					case "instance.url":
					case "instance_url":
					case "instanceUrl":
						ba.setInstanceUrl(value);
						break;
					case "vcs.url":
					case "vcs_url":
					case "vcsUrl":
						ba.setScmUrl(value);
						break;
					case "vcs.branch":
					case "vcs_branch":
					case "vcsBranch":
						ba.setScmBranch(value);
						break;
					case "vcs.revision":
					case "vcs_revision":
					case "vcsRevision":
						ba.setScmRevisionNumber(value);
						break;
					default:
						// MongoDB doesn't allow dots in keys. So we handle it by converting
						// the letter following it to uppercase, and ignoring the dot.
						if (key.contains(".")) {
							StringBuilder newKey = new StringBuilder();
							char prevChar = 0;
							for (char c : key.toCharArray()) {
								if (c != '.') {
									if (prevChar == '.') {
										c = Character.toUpperCase(c);
									}
									newKey.append(c);
								}
								prevChar = c;
							}
							key = newKey.toString();
						}
						if (StringUtils.isNotEmpty(key)) {
							ba.getMetadata().put(key, value);
						}
						break;
				}
			}
		}
	}

	// ////// Helpers

	private ResponseEntity<String> makeRestCall(String instanceUrl, String suffix) {
		ResponseEntity<String> response = null;
		String url = joinUrl(instanceUrl, artifactorySettings.getEndpoint(), suffix);
		try {
			HttpHeaders headers = createHeaders(instanceUrl);
			response = restClient.makeRestCallGet(url, headers);

		} catch (RestClientException re) {
			LOGGER.error("Error with REST url: " + url);
			LOGGER.error(re.getMessage());
		}
		return response;
	}

	private ResponseEntity<String> makeRestPost(String instanceUrl, String suffix, MediaType contentType, String body) {
		ResponseEntity<String> response = null;
		String url = joinUrl(instanceUrl, artifactorySettings.getEndpoint(), suffix);
		try {
			HttpHeaders headers = createHeaders(instanceUrl);
			headers.setContentType(contentType);
			headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
			response = restClient.makeRestCallPost(url, headers, body);

		} catch (RestClientException re) {
			LOGGER.error("Error with REST url: " + url);
			LOGGER.error(re.getMessage());
		}
		return response;
	}

	// join a base url to another path or paths - this will handle trailing or non-trailing /'s
	private String joinUrl(String url, String... paths) {
		StringBuilder result = new StringBuilder(url);
		for (String path : paths) {
			if (path != null) {
				String p = path.replaceFirst("^(\\/)+", "");
				if (result.lastIndexOf("/") != result.length() - 1) {
					result.append('/');
				}
				result.append(p);
			}
		}
		return result.toString();
	}

	protected HttpHeaders createHeaders(String instanceUrl) {
		HttpHeaders headers = new HttpHeaders();
		List<ServerSetting> servers = this.artifactorySettings.getServers();
		List<String> userNames = new ArrayList<>();
		List<String> apiKeys = new ArrayList<>();
		 servers.forEach(serverSetting -> {
			 userNames.add(serverSetting.getUsername());
			 apiKeys.add(serverSetting.getApiKey());
		});

		if (CollectionUtils.isNotEmpty(servers) && CollectionUtils.isNotEmpty(userNames) && CollectionUtils.isNotEmpty(apiKeys)) {
			for (int i = 0; i < servers.size(); i++) {
				ServerSetting serverSetting = servers.get(i);
				if (serverSetting != null && serverSetting.getUrl().equals(instanceUrl)
						&& i < userNames.size() && i < apiKeys.size() && userNames.get(i) != null && apiKeys.get(i) != null) {
					String userInfo = userNames.get(i) + ":" + apiKeys.get(i);
					byte[] encodedAuth = Base64.encodeBase64(
							userInfo.getBytes(StandardCharsets.US_ASCII));
					String authHeader = "Basic " + new String(encodedAuth);
					headers.set(HttpHeaders.AUTHORIZATION, authHeader);
				}
			}
		}
		return headers;
	}

	private JSONArray getJsonArray(JSONObject json, String key) {
		Object array = json.get(key);
		return array == null ? new JSONArray() : (JSONArray) array;
	}

	private String getString(JSONObject json, String key) {
		return (String) json.get(key);
	}

	private ArtifactItem createArtifactItem(String instanceUrl, String repo, String artName, String artPath) {
		ArtifactItem artifactItem = new ArtifactItem();
		artifactItem.setInstanceUrl(instanceUrl);
		artifactItem.setRepoName(repo);
		artifactItem.setArtifactName(artName);
		artifactItem.setPath(artPath);
		artifactItem.setDescription(artName);
		artifactItem.setLastUpdated(System.currentTimeMillis());
		return artifactItem;
	}

}
