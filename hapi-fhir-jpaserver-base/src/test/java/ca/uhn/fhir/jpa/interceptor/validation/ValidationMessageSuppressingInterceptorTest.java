package ca.uhn.fhir.jpa.interceptor.validation;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.config.BaseConfig;
import ca.uhn.fhir.jpa.provider.r4.BaseResourceProviderR4Test;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.interceptor.RequestValidatingInterceptor;
import ca.uhn.fhir.rest.server.interceptor.validation.ValidationMessageSuppressingInterceptor;
import ca.uhn.fhir.validation.IValidatorModule;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r5.utils.IResourceValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ValidationMessageSuppressingInterceptorTest extends BaseResourceProviderR4Test {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ValidationMessageSuppressingInterceptorTest.class);
	@Autowired
	private ApplicationContext myApplicationContext;
	@Autowired
	private IValidationSupport myValidationSupport;

	@Override
	@AfterEach
	public void after() throws Exception {
		super.after();
		myInterceptorRegistry.unregisterInterceptorsIf(t -> t instanceof RepositoryValidatingInterceptor);
		myInterceptorRegistry.unregisterInterceptorsIf(t -> t instanceof ValidationMessageSuppressingInterceptor);
	}

	@Test
	public void testDaoValidation() throws IOException {
		upload("/r4/uscore/CodeSystem-dummy-loinc.json");
		upload("/r4/uscore/StructureDefinition-us-core-pulse-oximetry.json");

		String input = loadResource("/r4/uscore/observation-pulseox.json");
		Observation inputObs = loadResource(myFhirCtx, Observation.class, "/r4/uscore/observation-pulseox.json");

		try {
			myObservationDao.validate(inputObs, null, input, null, null, null, null);
			fail();
		} catch (PreconditionFailedException e) {
			// good
		}

		ValidationMessageSuppressingInterceptor interceptor = new ValidationMessageSuppressingInterceptor();
		interceptor.addMessageSuppressionPatterns("Unknown code 'http://loinc.org#59408-5'");
		myInterceptorRegistry.registerInterceptor(interceptor);

		MethodOutcome validationOutcome = myObservationDao.validate(inputObs, null, input, null, null, null, null);
		OperationOutcome oo = (OperationOutcome) validationOutcome.getOperationOutcome();
		String encode = encode(oo);
		ourLog.info(encode);
		assertThat(encode, containsString("All observations should have a performer"));
	}

	@Test
	public void testRequestValidatingInterceptor() throws IOException {
		createPatient(withActiveTrue(), withId("AmyBaxter"));
		upload("/r4/uscore/CodeSystem-dummy-loinc.json");
		upload("/r4/uscore/StructureDefinition-us-core-pulse-oximetry.json");


		RequestValidatingInterceptor requestInterceptor = new RequestValidatingInterceptor();
		requestInterceptor.setFailOnSeverity(ResultSeverityEnum.ERROR);
		requestInterceptor.setValidatorModules(Collections.singletonList(new FhirInstanceValidator(myValidationSupport)));
		requestInterceptor.setInterceptorBroadcaster(myInterceptorRegistry);
		ourRestServer.registerInterceptor(requestInterceptor);


		// Without suppression
		{
			Observation inputObs = loadResource(myFhirCtx, Observation.class, "/r4/uscore/observation-pulseox.json");
			try {
				myClient.create().resource(inputObs).execute().getId().toUnqualifiedVersionless().getValue();
				fail();
			} catch (UnprocessableEntityException e) {
				String encode = encode(e.getOperationOutcome());
				ourLog.info(encode);
				assertThat(encode, containsString("Unknown code 'http://loinc.org#59408-5'"));
			}
		}

		// With suppression
		ValidationMessageSuppressingInterceptor interceptor = new ValidationMessageSuppressingInterceptor();
		interceptor.addMessageSuppressionPatterns("Unknown code 'http://loinc.org#59408-5'");
		myInterceptorRegistry.registerInterceptor(interceptor);
		{
			Observation inputObs = loadResource(myFhirCtx, Observation.class, "/r4/uscore/observation-pulseox.json");
			String id = myClient.create().resource(inputObs).execute().getId().toUnqualifiedVersionless().getValue();
			assertThat(id, matchesPattern("Observation/[0-9]+"));
		}
	}

	@Test
	public void testRepositoryValidation() {
		createPatient(withActiveTrue(), withId("A"));

		List<IRepositoryValidatingRule> rules = myApplicationContext.getBean(BaseConfig.REPOSITORY_VALIDATING_RULE_BUILDER, RepositoryValidatingRuleBuilder.class)
			.forResourcesOfType("Encounter")
			.requireValidationToDeclaredProfiles().withBestPracticeWarningLevel(IResourceValidator.BestPracticeWarningLevel.Ignore)
			.build();

		RepositoryValidatingInterceptor repositoryValidatingInterceptor = new RepositoryValidatingInterceptor();
		repositoryValidatingInterceptor.setFhirContext(myFhirCtx);
		repositoryValidatingInterceptor.setRules(rules);
		myInterceptorRegistry.registerInterceptor(repositoryValidatingInterceptor);

		// Without suppression
		try {
			Encounter encounter = new Encounter();
			encounter.setSubject(new Reference("Patient/A"));
			IIdType id = myEncounterDao.create(encounter).getId();
			assertEquals("1", id.getVersionIdPart());
			fail();
		} catch (PreconditionFailedException e) {
			String encode = encode(e.getOperationOutcome());
			ourLog.info(encode);
			assertThat(encode, containsString("Encounter.status: minimum required = 1"));
		}

		// With suppression
		ValidationMessageSuppressingInterceptor interceptor = new ValidationMessageSuppressingInterceptor();
		interceptor.addMessageSuppressionPatterns("Encounter.status");
		interceptor.addMessageSuppressionPatterns("Encounter.class");
		myInterceptorRegistry.registerInterceptor(interceptor);

		Encounter encounter = new Encounter();
		encounter.setSubject(new Reference("Patient/A"));
		IIdType id = myEncounterDao.create(encounter).getId().toUnqualifiedVersionless();
		assertThat(id.getValue(), matchesPattern("Encounter/[0-9]+"));

	}


}
