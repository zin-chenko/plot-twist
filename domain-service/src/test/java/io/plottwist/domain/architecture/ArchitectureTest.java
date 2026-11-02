package io.plottwist.domain.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "io.plottwist.domain",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule onlyBootstrapsInRoot =
      classes()
          .that()
          .resideInAPackage("io.plottwist.domain")
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
              "io.plottwist.domain.account.api..",
              "io.plottwist.domain.profile.api..",
              "io.plottwist.domain.chat.api..",
              "io.plottwist.domain.attachment.api..",
              "io.plottwist.domain.story.api..",
              "io.plottwist.domain.recommendation.api..",
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
              "io.plottwist.domain.account.api..",
              "io.plottwist.domain.profile.api..",
              "io.plottwist.domain.chat.api..",
              "io.plottwist.domain.attachment.api..",
              "io.plottwist.domain.story.api..",
              "io.plottwist.domain.recommendation.api..")
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
              "io.plottwist.domain.account.api..",
              "io.plottwist.domain.profile.api..",
              "io.plottwist.domain.chat.api..",
              "io.plottwist.domain.attachment.api..",
              "io.plottwist.domain.story.api..",
              "io.plottwist.domain.recommendation.api..");

  @ArchTest
  static final ArchRule featuresHaveNoCycles =
      slices().matching("io.plottwist.domain.(*)..").should().beFreeOfCycles();
}
