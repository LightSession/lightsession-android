# Regras que acompanham o SDK para o build do app cliente.
#
# O arquivo estava vazio. Sem ele, um release minificado quebrava o mapeamento de
# telas de formas silenciosas.

# O SDK identifica telas por nome de classe. Activities já são preservadas pelo
# keep que o AGP gera a partir do AndroidManifest, mas Fragments alcançados via
# Navigation Component não estão no manifesto — sem isto, `FragmentNavigator
# .Destination.className` devolve o nome ofuscado e o grafo vira "a -> b".
# -keepnames preserva o nome sem impedir a remoção de código não usado.
-keepnames class * extends androidx.fragment.app.Fragment
-keepnames class * extends android.app.Activity

# A introspecção de Compose não depende mais de comparar nome de classe
# (ver SkeletonGenerator.unwrap), então não é preciso preservar
# WrappedComposition nem CompositionImpl. Mantido como documentação do que
# NÃO é necessário, para ninguém reintroduzir a dependência.

# Modelos serializados pelo kotlinx.serialization.
-keepclassmembers class com.lightsession.** {
    *** Companion;
}
-keepclasseswithmembers class com.lightsession.** {
    kotlinx.serialization.KSerializer serializer(...);
}
