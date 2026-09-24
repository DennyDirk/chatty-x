package app.chattyx;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  @Test
  void businessLogicDoesNotDependOnNativeTelegramTypesOrControllers() {
    var classes = new ClassFileImporter().importPackages("app.chattyx");
    noClasses()
      .that()
      .resideInAnyPackage("..automation..", "..memory..", "..personas..", "..conversations..", "..delivery..")
      .should()
      .dependOnClassesThat()
      .haveSimpleName("TdTransport")
      .check(classes);
    noClasses()
      .that()
      .resideInAnyPackage("..automation..", "..memory..", "..conversations..", "..delivery..")
      .should()
      .dependOnClassesThat()
      .haveSimpleNameEndingWith("Controller")
      .check(classes);
  }
}
