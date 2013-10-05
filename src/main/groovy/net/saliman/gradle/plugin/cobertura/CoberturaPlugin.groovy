package net.saliman.gradle.plugin.cobertura

import org.gradle.StartParameter
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.UnknownConfigurationException
import org.gradle.api.tasks.testing.Test

import org.gradle.api.invocation.Gradle

/**
 * Provides Cobertura coverage for Test tasks.
 *
 * This plugin will create 4 tasks.
 *
 * The first is the "cobertura" task that users may call to generate coverage
 * reports.  This task will run the all tasks named "test" from the directory
 * where gradle was invoked as well as "test" tasks in any sub projects.  In
 * addition, it will run all tasks of type {@code Test} in the project applying
 * the plugin.  It is designed to do the same thing as "gradle test", but with
 * all testing tasks running in the applying project and coverage reports
 * generated at the end of the build.  This task doesn't actually do any work,
 * it is used by the plugin to determine user intent. This task will do the
 * right thing for most users.
 * <p>
 * The second task is called "coberturaReport". Users may call this task to
 * tell Gradle that coverage reports should be generated after tests are run.
 * No tests are actually added to build, this will have to be done by adding
 * testing tasks to the gradle command line. This allows more fine-grained
 * control over the tasks that get run
 * <p>
 * The third task is the "instrument" task, that will instrument the source
 * code. Users won't call it directly, but the plugin will make all test
 * tasks depend on it so that instrumentation only happens once, and only if
 * the task graph has the "cobertura" or "coberturaReport" task in the execution
 * graph.
 * <p>
 * The fourth task is the "generateCoberturaReport" task, which does the actual
 * work of generating the coverage reports if the "cobertura" or
 * "coberturaReprot" taks are in the execution graph.
 * <p>
 * This plugin also defines a "cobertura" extension with properties that are
 * used to configure the operation of the plugin and its tasks.
 *
 * The plugin runs cobertura coverage reports for sourceSets.main.  A project
 * might have have multiple artifacts that use different parts of the code, and
 * there may be different test tasks that test different parts of the source,
 * but there is almost always only one main source set.
 *
 * Most of the magic of this plugin happens not at apply time, but when tasks
 * are added and when the task graph is ready.  If the graph contains the
 * "cobertura" or "coberturaReport" task, it will make sure the instrument and
 * generateCoberturaReport" tasks are configured to do actual work, and that
 * task dependencies are what they need to be for the users to get what they
 * want.
 */
class CoberturaPlugin implements Plugin<Project> {

	def void apply(Project project) {
		project.logger.info("Applying cobertura plugin to $project.name")
		// It doesn't make sense to have the cobertura plugin without the java
		// plugin because it works with java classes, so apply it here.  If the
		// project is a Groovy or Scala project, we're still good because Groovy
		// and Scala compiles to java classes under the hood, and the Groovy and
		// Scala plugins will extend the Java plugin anyway.
		project.apply plugin: 'java'
		project.extensions.coberturaRunner = new CoberturaRunner()

		project.extensions.create('cobertura', CoberturaExtension, project)
		if (!project.configurations.asMap['cobertura']) {
			project.configurations.create('cobertura') {
				extendsFrom project.configurations['testCompile']
			}
			project.dependencies {
				cobertura "net.sourceforge.cobertura:cobertura:${project.extensions.cobertura.coberturaVersion}"
			}
		}
		project.dependencies.add('testRuntime', "net.sourceforge.cobertura:cobertura:${project.extensions.cobertura.coberturaVersion}")

		createTasks(project)

		Project baseProject = findBaseProject(project);

		fixTaskDependencies(project, baseProject)

		registerTaskFixupListener(project.gradle)

	}

	/**
	 * Create the tasks that the Cobertura plugin will use.  This method will
	 * create the following tasks:
	 * <ul>
	 * <li>instrument - This is an internal task that does the actual work of
	 * instrumenting the source code.</li>
	 * <li>generateCoberturaReport - This is an internal task that does the
	 * actual work of generating the Cobertura reports.</li>
	 * <li>coberturaReport - Users will use this task to tell the plugin that
	 * they want coverage reports generated after all the tests run.  The
	 * {@code coberturaReport} task doesn't actually cause any tests to run;
	 * users will need to specify one or more test tasks on the command line
	 * as well.</li>
	 * <li>cobertura</li> - Users will use this task to run tests and generate
	 * a coverage report.  This task is meant to be a convenience task that is
	 * simpler than (but not quite the same as) {@code test coberturaReport}</li>
	 * </ul>
	 *
	 * @param project the project being configured
	 */
	private void createTasks(Project project) {
		// Create the instrument task, but don't have it do anything yet because we
		// don't know if we need to run cobertura yet. We need to process all new
		// tasks as they are added, so we can't use withType.
		project.tasks.create(name: 'instrument', type: InstrumentTask, {configuration = project.extensions.cobertura})
		InstrumentTask instrumentTask = project.tasks.getByName("instrument")
		instrumentTask.setDescription("Instrument code for Cobertura coverage reports")
		instrumentTask.runner = project.extensions.coberturaRunner

		// Create the generateCoberturaReport task.  It doesn't do anything yet
		// either.
		project.tasks.create(name: 'generateCoberturaReport', type: GenerateReportTask, {configuration = project.extensions.cobertura})
		GenerateReportTask generateReportTask = project.tasks.getByName("generateCoberturaReport")
		generateReportTask.setDescription("Helper task that does the actual Cobertura report generation")
		generateReportTask.runner = project.extensions.coberturaRunner

		// Create the coberturaReport task.
		project.tasks.create(name: 'coberturaReport', type:  DefaultTask)
		DefaultTask reportTask = project.tasks.getByName("coberturaReport")
		reportTask.setDescription("Generate Cobertura reports after tests finish.")

		// Create the cobertura task.
		project.tasks.create(name: 'cobertura', type:  DefaultTask)
		Task coberturaTask = project.tasks.getByName("cobertura")
		coberturaTask.setDescription("Run tests and generate Cobertura coverage reports.")
	}

	/**
	 * Find the base project for the build.  Basically, this means the project
	 * at the directory where gradle was invoked.  This is not always the root
	 * project, or the project applying the cobertura plugin.  For example,
	 * If we have a project called parent, which has a child project named child,
	 * which has a child named grandchild.  Assume the cobertura plugin is applied
	 * to the grandchild project.  If you invoke gradle from the child directory,
	 * the base directory would be the child project. The cobertura task will
	 * need to depend on the test task of this base project in order to run the
	 * same tasks as typing {@code gradle test} from the child directory.
	 * @param project the project applying the cobertura plugin.
	 * @return the project object representing the project in the directory where
	 * gradle was invoked.
	 */
	private Project findBaseProject(Project project) {
		StartParameter sp = project.gradle.startParameter
		Project baseProject = null;
		project.rootProject.allprojects.each { possibleBase ->
			if ( possibleBase.projectDir.equals(sp.projectDir) ) {
				project.logger.info("Found base project ${possibleBase.name}")
				baseProject = possibleBase
			}
		}
		return baseProject
	}

	/**
	 * We need to make several changes to the tasks in the task graph for the
	 * cobertura plugin to work correctly.  The changes need to be made to all
	 * tasks currently in the project, as well as any new tasks that get added
	 * later.
	 * @param project the project applying the plugin. Used for logging.
	 * @param baseProject the project in the directory from which gradle was
	 * invoked.
	 * @see #fixTaskDependency(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)
	 */
	private void fixTaskDependencies(Project project, Project baseProject) {
		InstrumentTask instrumentTask = project.tasks.getByName("instrument")
		GenerateReportTask generateReportTask = project.tasks.getByName("generateCoberturaReport")
		Task reportTask = project.tasks.getByName("coberturaReport")
		Task coberturaTask = project.tasks.getByName("cobertura")

		// If the user put the cobertura task on the command line, we need reports.
		coberturaTask.dependsOn reportTask

		// Add a whenTaskAdded listener for all projects from the base down.
		baseProject.allprojects.each { p ->
			p.tasks.each { task ->
				project.logger.info("Fixing task :${task.project.name}:${task.name}")
				fixTaskDependency(project, task, instrumentTask, generateReportTask, coberturaTask)
			}
			p.tasks.whenTaskAdded { Task task ->
				project.logger.info("Adding task :${task.project.name}:${task.name}")
				fixTaskDependency(project, task, instrumentTask, generateReportTask, coberturaTask)
			}
		}
	}

	/**
	 * Helper to the helper that does the actual work of fixing the dependencies
	 * of a single task.  It checks for several things, and makes needed changes:
	 * <ol>
	 * <li>Tasks in the applying project of type {@code Test}, it needs to depend
	 * on the instrument task.</li>
	 * <li>Tasks in the applying project of type {@code Test} need to be finalized
	 * by the generateReportTask so that reports get generated when requested by
	 * the user.</li>
	 * <li>The cobertura task needs to depend on tasks in the applying project of
	 * type {@code Test}
	 * <li>If the task is named "test", the cobertura task needs to depend on it
	 * </li>
	 * <li>The cobertura task also needs to depend on the coberturaReport task so
	 * that we get a report at the end.</li>
	 * <li>The instrument task needs to depend on the task if it is named
	 * "classes", and is in the applying project.</li>
	 * </ol>
	 * @param project the project applying the plugin..
	 * @param task the task being checked.
	 * @param instrumentTask the instrument task
	 * @param generateReportTask the generateReportTask
	 * @param coberturaTask the coberturaTask
	 */
	private void fixTaskDependency(project, task, instrumentTask, generateReportTask,
	                               coberturaTask) {
		// If the task is a test tasks in the applying project, it needs to depend
		// on the instrument task, and be finalized by the report task.
		// This doesn't actually change what runs, it just establishes order.
		if ( task instanceof  Test && task.project == project ) {
			project.logger.info("Changing dependencies for task :${task.project.name}:${task.name}")
			task.dependsOn 'instrument'
			task.finalizedBy generateReportTask
			coberturaTask.dependsOn task
		}

		// If the task is named "test", then the cobertura task needs to depend
		// on it so that "gradle cobertura" and "gradle test" runs the same
		// tests.  This is independent of what project the task is in because we
	  // only get called for the base project down.
		if ( task.name == "test" ) {
			project.logger.info("Making the cobertura task depend on :${task.project.name}:${task.name}")
			coberturaTask.dependsOn task
		}
		// If the task is a "classes" task in the applying project, then
		// instrumentation depends on it.
		if ( task.name == "classes" && task.project == project ) {
			project.logger.info("Making the instrument task depend on :${task.project.name}:${task.name}")
			instrumentTask.dependsOn task
		}
	}

	/**
	 * Register a listener with Gradle.  When gradle is ready to run tasks, it
	 * will call our listener.  If the coberturaReport task is in our graph, the
	 * listener will fix the classpaths of all the test tasks that we are actually
	 * running.
	 *
	 * @param gradle the gradle instance running the plugin.
	 */
	private void registerTaskFixupListener(Gradle gradle) {
		// If we need to run cobertura reports, fix test classpaths, and set them
		// to generate reports on failure.  If not, disable instrumentation.
		// "whenReady()" is a global event, so closure should be registered exactly
		// once for single and multi-project builds.
		if ( !gradle.ext.has('coberturaPluginListenerRegistered') ) {
			gradle.ext.coberturaPluginListenerRegistered = true
			gradle.taskGraph.whenReady { graph ->
				if (graph.allTasks.find { it.name == "coberturaReport" } != null) {
					// We're running coberturaReport, so fix the classpath of any test
					// task we are actually running.
					graph.allTasks.findAll { it instanceof Test}.each { Test test ->
						try {
							Configuration config = test.project.configurations['cobertura']
							test.systemProperties.put('net.sourceforge.cobertura.datafile', test.project.extensions.cobertura.coverageDatafile)
							test.classpath += config
							fixTestClasspath(test)
						} catch (UnknownConfigurationException e) {
							// Eat this. It just means we have a multi-project build, and
							// there is test in a project that doesn't have cobertura applied.
						}
					}
				} else {
					// We're not running coberturaReport, so disable all instrument and
					// report tasks.
					graph.allTasks.findAll { it instanceof InstrumentTask}.each {
						it.enabled = false
					}
					graph.allTasks.findAll { it instanceof GenerateReportTask}.each {
						it.enabled = false
					}
				}
			}
		}
	}

	/**
	 * Configure a test task.  remove source dirs and add the instrumented dir
	 * @param test the test task to fix
	 */
	def fixTestClasspath(Task test) {
		def project = test.project
		project.files(project.sourceSets.main.output.classesDir.path).each { File f ->
			if (f.isDirectory()) {
				test.classpath = test.classpath - project.files(f)
			}
		}
		test.classpath = project.files("${project.buildDir}/instrumented_classes") + test.classpath
	}
}
