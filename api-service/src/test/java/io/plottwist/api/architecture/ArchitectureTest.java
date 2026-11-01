package io.plottwist.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.plottwist.api", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule onlyBootstrapsInRoot =
      classes()
          .that()
          .resideInAPackage("io.plottwist.api")
          .should()
          .haveSimpleNameEndingWith("Application");

  @ArchTest
  static final ArchRule modelsAreFrameworkFree =
      noClasses()
          .that()
          .resideInAPackage("..model..")
          .should()
          .dependOnClassesThat()
          .resideOutsideOfPackages("java..", "..model..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule useCasesDoNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.plottwist.api.account.api..",
              "io.plottwist.api.profile.api..",
              "io.plottwist.api.story.api..",
              "..infrastructure..",
              "org.springframework.jdbc..",
              "software.amazon.awssdk..",
              "jakarta.servlet..",
              "org.springframework.web..");

  @ArchTest
  static final ArchRule controllersDoNotUseInfrastructure =
      noClasses()
          .that()
          .resideInAnyPackage(
              "io.plottwist.api.account.api..",
              "io.plottwist.api.profile.api..",
              "io.plottwist.api.story.api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..infrastructure..",
              "org.springframework.jdbc..",
              "software.amazon.awssdk..",
              "org.springframework.web.client..");

  @ArchTest
  static final ArchRule adaptersDoNotUseHttpDtos =
      noClasses()
          .that()
          .resideInAPackage("..infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.plottwist.api.account.api..",
              "io.plottwist.api.profile.api..",
              "io.plottwist.api.story.api..");

  @ArchTest
  static final ArchRule featuresHaveNoCycles =
      slices().matching("io.plottwist.api.(*)..").should().beFreeOfCycles();
}
