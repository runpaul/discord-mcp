package dev.saseq.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;

class ToolClassificationTest {

	private Set<String> discoverAllToolNames() throws ClassNotFoundException {
		Set<String> discoveredTools = new HashSet<>();
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

		for (BeanDefinition beanDef : scanner.findCandidateComponents("dev.saseq.services")) {
			String className = beanDef.getBeanClassName();
			Class<?> clazz = Class.forName(className);

			for (Method method : clazz.getDeclaredMethods()) {
				Tool toolAnnotation = method.getAnnotation(Tool.class);
				if (toolAnnotation != null) {
					discoveredTools.add(toolAnnotation.name());
				}
			}
		}

		return discoveredTools;
	}

	@Test
	void testAllToolsAreClassified() throws ClassNotFoundException {
		Set<String> discoveredTools = discoverAllToolNames();

		assertTrue(discoveredTools.size() > 0, "Should discover at least one tool");

		Set<String> unclassified = new HashSet<>(discoveredTools);
		unclassified.removeAll(ToolClassification.DESTRUCTIVE);
		unclassified.removeAll(ToolClassification.READ_ONLY);
		unclassified.removeAll(ToolClassification.WRITE_NON_DESTRUCTIVE);

		assertTrue(unclassified.isEmpty(), "Unclassified tools: " + unclassified);
	}

	@Test
	void testSetsArePairwiseDisjoint() {
		Set<String> overlap1 = new HashSet<>(ToolClassification.DESTRUCTIVE);
		overlap1.retainAll(ToolClassification.READ_ONLY);
		assertTrue(overlap1.isEmpty(), "DESTRUCTIVE and READ_ONLY overlap: " + overlap1);

		Set<String> overlap2 = new HashSet<>(ToolClassification.DESTRUCTIVE);
		overlap2.retainAll(ToolClassification.WRITE_NON_DESTRUCTIVE);
		assertTrue(overlap2.isEmpty(), "DESTRUCTIVE and WRITE_NON_DESTRUCTIVE overlap: " + overlap2);

		Set<String> overlap3 = new HashSet<>(ToolClassification.READ_ONLY);
		overlap3.retainAll(ToolClassification.WRITE_NON_DESTRUCTIVE);
		assertTrue(overlap3.isEmpty(), "READ_ONLY and WRITE_NON_DESTRUCTIVE overlap: " + overlap3);
	}

	@Test
	void testAllClassifiedToolsExist() throws ClassNotFoundException {
		Set<String> discoveredTools = discoverAllToolNames();

		Set<String> classifiedButNotFound = new HashSet<>();
		classifiedButNotFound.addAll(ToolClassification.DESTRUCTIVE);
		classifiedButNotFound.addAll(ToolClassification.READ_ONLY);
		classifiedButNotFound.addAll(ToolClassification.WRITE_NON_DESTRUCTIVE);
		classifiedButNotFound.removeAll(discoveredTools);

		assertTrue(classifiedButNotFound.isEmpty(), "Classified but not found: " + classifiedButNotFound);
	}

	@Test
	void testDeleteToolsAreDestructive() {
		for (String tool : ToolClassification.DESTRUCTIVE) {
			if (tool.startsWith("delete_")) {
				assertTrue(true);
			}
		}

		// All delete_* must be DESTRUCTIVE
		for (String tool : ToolClassification.READ_ONLY) {
			assertFalse(tool.startsWith("delete_"), "Tool " + tool + " should not be READ_ONLY");
		}
		for (String tool : ToolClassification.WRITE_NON_DESTRUCTIVE) {
			assertFalse(tool.startsWith("delete_"), "Tool " + tool + " should not be WRITE_NON_DESTRUCTIVE");
		}
	}

	@Test
	void testSpecificDestructiveTools() {
		assertTrue(ToolClassification.DESTRUCTIVE.contains("kick_member"));
		assertTrue(ToolClassification.DESTRUCTIVE.contains("ban_member"));
		assertTrue(ToolClassification.DESTRUCTIVE.contains("timeout_member"));
		assertTrue(ToolClassification.DESTRUCTIVE.contains("remove_role"));
		assertTrue(ToolClassification.DESTRUCTIVE.contains("disconnect_member"));
		assertTrue(ToolClassification.DESTRUCTIVE.contains("modify_voice_state"));
	}

	@Test
	void testIsMutatingMethod() {
		// DESTRUCTIVE tools should be mutating
		for (String tool : ToolClassification.DESTRUCTIVE) {
			assertTrue(ToolClassification.isMutating(tool), tool + " should be mutating");
		}

		// WRITE_NON_DESTRUCTIVE tools should be mutating
		for (String tool : ToolClassification.WRITE_NON_DESTRUCTIVE) {
			assertTrue(ToolClassification.isMutating(tool), tool + " should be mutating");
		}

		// READ_ONLY tools should NOT be mutating
		for (String tool : ToolClassification.READ_ONLY) {
			assertFalse(ToolClassification.isMutating(tool), tool + " should not be mutating");
		}
	}

	@Test
	void testOfMethod() throws ClassNotFoundException {
		Set<String> discoveredTools = discoverAllToolNames();

		for (String tool : discoveredTools) {
			assertTrue(ToolClassification.of(tool).isPresent(), "Tool " + tool + " should have a classification");
		}

		// Non-existent tool should return empty
		assertFalse(ToolClassification.of("non_existent_tool").isPresent());
	}

	@Test
	void testDiscoveredToolCount() throws ClassNotFoundException {
		Set<String> discoveredTools = discoverAllToolNames();
		assertEquals(75, discoveredTools.size(), "Should discover exactly 75 tools");
	}

}
