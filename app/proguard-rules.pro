# Fylz release shrinking rules. Keep every rule here explicit and narrowly scoped.

# ---------------------------------------------------------------------------
# smbj (SMB remote provider) -> mbassy -> javax.el
#
# mbassy's ElFilter offers optional Expression-Language message filtering and
# references javax.el.*, a Java EE API that does not exist on Android and that smbj
# never activates. R8 refuses to finish while these references are unresolved.
# Fylz does not use mbassy's EL filtering, so the warnings are noise: -dontwarn,
# not -keep, because the classes genuinely must not be shipped.
# ---------------------------------------------------------------------------
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper

# ---------------------------------------------------------------------------
# smbj -> org.ietf.jgss (SPNEGO / Kerberos)
#
# SpnegoAuthenticator uses the JDK's GSS-API for Kerberos single sign-on. Android's
# runtime has no org.ietf.jgss package, and Fylz only ever builds an
# AuthenticationContext from a username and password -- SmbProviderConfig rejects
# anonymous sessions and requires a non-empty password -- so the Kerberos path is
# unreachable at runtime. Suppressed for the same reason as above.
# ---------------------------------------------------------------------------
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

# ---------------------------------------------------------------------------
# Fylz's own DocumentsProvider is instantiated reflectively by the platform from the
# <provider> declaration in AndroidManifest.xml, so R8 sees no call site for its
# constructor and would otherwise be free to strip or rename it.
# ---------------------------------------------------------------------------
-keep class io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider { *; }
