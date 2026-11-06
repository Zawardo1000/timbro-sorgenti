package it.zawardo.timbro.acquisizione;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.impl.Observable;
import androidx.camera.core.impl.Timebase;
import androidx.camera.video.EncoderProfilesResolver;
import androidx.camera.video.MediaSpec;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapabilities;
import androidx.camera.video.VideoOutput;

import java.util.function.Consumer;

/**
 * L'uscita per VideoCapture della codifica compatta (RegistratoreCompatto.kt): la superficie dei fotogrammi va al
 * nostro codificatore, tutto il resto (MediaSpec con la scelta della qualità, capacità e profili del telefono) viene
 * dal Recorder di sempre, che così non registra mai ma decide la risoluzione come prima. Lo stato del flusso resta
 * quello predefinito di VideoOutput: sempre attivo.
 *
 * In Java e non in Kotlin perché getEncoderProfilesResolver restituisce una classe di CameraX scritta in Kotlin come
 * "internal": dal nostro Kotlin non si può nominare, da Java sì (per la JVM è pubblica). API riservate di CameraX: con
 * la versione fissata nel catalogo funzionano, a ogni aggiornamento di CameraX va riprovata.
 */
@SuppressLint("RestrictedApi")
final class UscitaCompatta implements VideoOutput {
    private final Recorder base;
    private final Consumer<SurfaceRequest> superficie;

    UscitaCompatta(@NonNull Recorder base, @NonNull Consumer<SurfaceRequest> superficie) {
        this.base = base;
        this.superficie = superficie;
    }

    @Override
    public void onSurfaceRequested(@NonNull SurfaceRequest request) {
        superficie.accept(request);
    }

    @Override
    public void onSurfaceRequested(@NonNull SurfaceRequest request, @NonNull Timebase timebase, boolean hasGlProcessing) {
        superficie.accept(request);
    }

    @NonNull
    @Override
    public Observable<MediaSpec> getMediaSpec() {
        return base.getMediaSpec();
    }

    @NonNull
    @Override
    public VideoCapabilities getMediaCapabilities(@NonNull CameraInfo cameraInfo, int sessionType) {
        return base.getMediaCapabilities(cameraInfo, sessionType);
    }

    @NonNull
    @Override
    public EncoderProfilesResolver getEncoderProfilesResolver(@NonNull CameraInfo cameraInfo, int sessionType) {
        return base.getEncoderProfilesResolver(cameraInfo, sessionType);
    }

    @Override
    public boolean isQualitySelectorDefault() {
        return base.isQualitySelectorDefault();
    }
}
