package com.jetbrains.python.psi.types;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiElement;
import com.intellij.psi.ResolveState;
import com.intellij.util.ProcessingContext;
import com.intellij.util.SmartList;
import com.jetbrains.python.codeInsight.PyDynamicMember;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyClassImpl;
import com.jetbrains.python.psi.patterns.ParentMatcher;
import com.jetbrains.python.psi.resolve.PyResolveUtil;
import com.jetbrains.python.psi.resolve.ResolveProcessor;
import com.jetbrains.python.psi.resolve.VariantsProcessor;
import com.jetbrains.python.psi.stubs.PyClassNameIndex;
import com.jetbrains.python.toolbox.Maybe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author yole
 */
public class PyClassType implements PyType {

  protected final PyClass myClass;
  protected final boolean myIsDefinition;

  /**
   * Describes a class-based type. Since everything in Python is an instance of some class, this type pretty much completes
   * the type system :)
   * Note that classes' and instances' member list can change during execution, so it is important to construct an instance of PyClassType
   * right in the place of reference, so that such changes could possibly be accounted for.
   *
   * @param source        PyClass which defines this type. For builtin or external classes, skeleton files contain the definitions.
   * @param is_definition whether this type describes an instance or a definition of the class.
   */
  public PyClassType(final @Nullable PyClass source, boolean is_definition) {
    myClass = source;
    myIsDefinition = is_definition;
  }

  /**
   * @return a PyClass which defined this type.
   */
  @Nullable
  public PyClass getPyClass() {
    return myClass;
  }

  /**
   * @return whether this type refers to an instance or a definition of the class.
   */
  public boolean isDefinition() {
    return myIsDefinition;
  }

  @Nullable
  public String getClassQName() {
    return myClass == null ? null : myClass.getQualifiedName();
  }

  @Nullable
  public List<? extends PsiElement> resolveMember(final String name, AccessDirection direction) {
    assert myClass != null;
    Property property = myClass.findProperty(name);
    if (property != null) {
      Maybe<PyFunction> accessor = property.getByDirection(direction);
      if (accessor.isDefined()) {
        Callable accessor_code = accessor.value();
        SmartList<PsiElement> ret = new SmartList<PsiElement>();
        if (accessor_code != null) ret.add(accessor_code);
        PyTargetExpression site = property.getDefinitionSite();
        if (site != null) ret.add(site);
        if (ret.size() > 0) return ret;
        else return null; // property is found, but the required accessor is explicitly absent
      }
    }
    final PsiElement classMember = resolveClassMember(myClass, name);
    if (classMember != null) {
      return new SmartList<PsiElement>(classMember);
    }

    boolean hasSuperClasses = false;
    for (PyClass superClass : myClass.iterateAncestors()) {
      hasSuperClasses = true;
      PsiElement superMember = resolveClassMember(superClass, name);
      if (superMember != null) {
        return new SmartList<PsiElement>(superMember);
      }
    }
    if (!hasSuperClasses) {
      // no superclasses, try old-style
      // TODO: in py3k, 'object' is the default base, not <classobj>
      if (getClass() != null) {
        PyClassType oldstyle = PyBuiltinCache.getInstance(myClass).getOldstyleClassobjType();
        if (oldstyle != null) {
          final PyClass myclass = getPyClass();
          if (myclass != null) {
            final String myname = myclass.getName();
            final PyClass oldstyleclass = oldstyle.getPyClass();
            if (oldstyleclass != null) {
              final String oldstylename = oldstyleclass.getName();
              if ((myname != null) && (oldstylename != null) && !myname.equals(oldstylename) && !myname.equals("object")) {
                return oldstyle.resolveMember(name, direction);
              }
            }
          }
        }
      }
    }
    return Collections.emptyList();
  }

  @Nullable
  private static PsiElement resolveClassMember(PyClass aClass, String name) {
    PsiElement result = resolveInner(aClass, name);
    if (result != null) {
      return result;
    }
    for (PyClassMembersProvider provider : Extensions.getExtensions(PyClassMembersProvider.EP_NAME)) {
      final PsiElement resolveResult = provider.resolveMember(aClass, name);
      if (resolveResult != null) return resolveResult;
    }

    return null;
  }

  @Nullable
  private static PsiElement resolveInner(PyClass aClass, String name) {
    ResolveProcessor processor = new ResolveProcessor(name);
    ((PyClassImpl) aClass).processDeclarations(processor); // our members are strictly within us.
    final PsiElement resolveResult = processor.getResult();
    //final PsiElement resolveResult = PyResolveUtil.treeWalkUp(new PyResolveUtil.ResolveProcessor(name), myClass, null, null);
    if (resolveResult != null && resolveResult != aClass) {
      return resolveResult;
    }
    return null;
  }

  public Object[] getCompletionVariants(final PyQualifiedExpression referenceExpression, ProcessingContext context) {
    List<? extends PsiElement> classList = new ParentMatcher(PyClass.class).search(referenceExpression);
    boolean withinOurClass = classList != null && classList.get(0) == this;
    Set<String> namesAlready = context.get(CTX_NAMES);
    List<Object> ret = new ArrayList<Object>();
    Condition<String> underscoreFilter = new PyUtil.UnderscoreFilter(PyUtil.getInitialUnderscores(referenceExpression.getName()));
    // from providers
    for (PyClassMembersProvider provider : Extensions.getExtensions(PyClassMembersProvider.EP_NAME)) {
      for (PyDynamicMember member : provider.getMembers(myClass)) {
        final String name = member.getName();
        if (underscoreFilter.value(name) || provider.hasUnderscoreWildCard(name)) {
          ret.add(LookupElementBuilder.create(name).setIcon(member.getIcon()).setTypeText(member.getShortType()));
        }
      }
    }
    // from our own class
    final VariantsProcessor processor = new VariantsProcessor(
      referenceExpression, new PyResolveUtil.FilterNotInstance(myClass), underscoreFilter
    );
    ((PyClassImpl) myClass).processDeclarations(processor);
    if (namesAlready != null) {
      for (LookupElement le : processor.getResultList()) {
        String name = le.getLookupString();
        if (namesAlready.contains(name)) continue;
        if (!withinOurClass && isClassPrivate(name)) continue;
        namesAlready.add(name);
        ret.add(le);
      }
    }
    else {
      ret.addAll(processor.getResultList());
    }
    for (PyClass ancestor : myClass.getSuperClasses()) {
      Object[] ancestry = (new PyClassType(ancestor, true)).getCompletionVariants(referenceExpression, context);
      for (Object ob : ancestry) {
        if (ob instanceof LookupElementBuilder) {
          final LookupElementBuilder lookupElt = (LookupElementBuilder)ob;
          if (!isClassPrivate(lookupElt.getLookupString())) ret.add(lookupElt.setTypeText(ancestor.getName()));
        }
        else {
          if (!isClassPrivate(ob.toString())) ret.add(ob);
        }
      }
      ret.addAll(Arrays.asList(ancestry));
    }
    return ret.toArray();
  }

  private static boolean isClassPrivate(String lookup_string) {
    return lookup_string.startsWith("__") && !lookup_string.endsWith("__");
  }

  public String getName() {
    PyClass cls = getPyClass();
    if (cls != null) {
      return cls.getName();
    }
    else {
      return null;
    }
  }

  @NotNull
  public Set<String> getPossibleInstanceMembers() {
    Set<String> ret = new HashSet<String>();
    /*
    if (myClass != null) {
      PyClassType otype = PyBuiltinCache.getInstance(myClass.getProject()).getObjectType();
      ret.addAll(otype.getPossibleInstanceMembers());
    }
    */
    // TODO: add our own ideas here, e.g. from methods other than constructor
    return Collections.unmodifiableSet(ret);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    PyClassType classType = (PyClassType)o;

    if (myIsDefinition != classType.myIsDefinition) return false;
    if (myClass != null ? !myClass.equals(classType.myClass) : classType.myClass != null) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = myClass != null ? myClass.hashCode() : 0;
    result = 31 * result + (myIsDefinition ? 1 : 0);
    return result;
  }

  public static boolean is(@NotNull String qName, PyType type) {
    if (type instanceof PyClassType) {
      return qName.equals(((PyClassType)type).getClassQName());
    }
    return false;
  }

  @Override
  public String toString() {
    return "PyClassType: " + getClassQName();
  }

  public static PyClassType fromClassName(String typeName, Project project) {
    PyClass clazz = PyClassNameIndex.findClass(typeName, project);
    return new PyClassType(clazz, true);
  }
}
