# Cómo abrir este proyecto

## 1. Generar el wrapper (solo la primera vez)

El repositorio trae `gradle/wrapper/gradle-wrapper.properties`, que fija la
versión de Gradle, pero le falta el `.jar` y los scripts `gradlew` porque son
binarios. Se generan con un comando, desde esta carpeta:

```bash
gradle wrapper
```

Si `gradle` no está en tu PATH, usa el que trae Android Studio:

- **macOS / Linux**
  `/Applications/Android\ Studio.app/Contents/gradle/gradle-*/bin/gradle wrapper`
- **Windows**
  `"C:\Program Files\Android\Android Studio\gradle\gradle-*\bin\gradle.bat" wrapper`

Después de eso aparecen `gradlew`, `gradlew.bat` y
`gradle/wrapper/gradle-wrapper.jar`. Esos sí van al repositorio.

## 2. Abrir en Android Studio

`File → Open` y elige **esta carpeta** (`android/`), no la raíz del proyecto.
Si abres la raíz, Gradle no encuentra `settings.gradle.kts` y te dirá que no
es un proyecto Android.

## 3. Comprobar que usa el wrapper

`Settings → Build, Execution, Deployment → Build Tools → Gradle`

- **Distribution:** `Wrapper` (no "Specified location" ni "Local installation")
- **Gradle JDK:** cualquiera de la **17**

Con "Distribution: Wrapper", Android Studio respeta la versión fijada en
`gradle-wrapper.properties` y deja de usar la que tenga instalada.

## 4. Sincronizar

La primera sincronización descarga Gradle 8.11.1, el SDK de Android y las
dependencias de Compose y Firebase. Tarda un buen rato y parece colgada.
Déjala terminar; cancelar a mitad deja el caché a medias y produce errores
raros después.

## Si algo falla

**`Task 'prepareKotlinBuildScriptModel' not found`**
Gradle y AGP no se llevan. Casi siempre es que el IDE no está usando el
wrapper: revisa el paso 3.

**`Unsupported class file major version`** o errores de Java
El Gradle JDK no es 17. Paso 3, segunda línea.

**`SDK location not found`**
Falta `local.properties`. Android Studio lo crea solo; si compilas desde la
terminal, copia `local.properties.example` y pon la ruta de tu SDK.

**Limpiar y volver a empezar**
```bash
./gradlew --stop
rm -rf .gradle build app/build
```
Y en el IDE: `File → Invalidate Caches → Invalidate and Restart`.
