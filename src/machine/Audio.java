/*
 *	Audio.java
 *
 *  2009-2010 Jos� Luis S�nchez
 *
 *  with parts from:
 *	Copyright 2007-2008 Jan Bobrowski <jb@wizard.ae.krakow.pl>
 *
 *	This program is free software; you can redistribute it and/or
 *	modify it under the terms of the GNU General Public License
 *	version 2 as published by the Free Software Foundation.
 */
package machine;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import configuration.AY8912Type;

class Audio {
    private int samplingFrequency;
    private SourceDataLine line;
    private DataLine.Info infoDataLine;
    private AudioFormat fmt;
    private SourceDataLine sdl;
    // Buffer de sonido para el frame actual, hay m�s espacio del necesario.
    private final byte[] buf = new byte[4096];
    private final int[] beeper = new int[1024];
    // Buffer de sonido para el AY
    private final int[] ayBufA = new int[1024];
    private final int[] ayBufB = new int[1024];
    private final int[] ayBufC = new int[1024];
    private int bufp;
    private int level, lastLevel;
    private int audiotstates;
    private int samplesPerFrame, frameSize, bufferSize;
    private int soundMode;
    private float timeRem, spf;
    private MachineTypes spectrumModel;
    private boolean enabledAY;
    private AY8912Type settings;
    private AY8912 ay;

    Audio(AY8912Type ayConf) {
       settings = ayConf;
       line = null;
       samplingFrequency = 32000;
    }
    
    synchronized void open(MachineTypes model, AY8912 ay8912, boolean hasAY, int freq) {
        samplingFrequency = freq;
        soundMode = settings.getSoundMode();
        if (soundMode < 0 || soundMode > 3)
            soundMode = 0;
//              System.out.println("Selected soundMode " +  soundMode);

        int channels =  soundMode > 0 ? 2 : 1;

        if (line == null) {
            try {
                fmt = new AudioFormat(samplingFrequency, 16, channels, true, false);
//                System.out.println(fmt);
                infoDataLine = new DataLine.Info(SourceDataLine.class, fmt);
                sdl = (SourceDataLine) AudioSystem.getLine(infoDataLine);
            } catch (Exception excpt) {
                Logger.getLogger(Audio.class.getName()).log(Level.SEVERE, null, excpt);
            }

            enabledAY = hasAY;
            timeRem = 0.0f;
            samplesPerFrame = samplingFrequency / 50;
            frameSize = samplesPerFrame * 2 * channels;
            if (model != spectrumModel) {
                spectrumModel = model;
                ay8912.setSpectrumModel(spectrumModel);
            }
            spf = (float) spectrumModel.getTstatesFrame() / samplesPerFrame;
            audiotstates = bufp = level = lastLevel = 0;
            if (soundMode > 0) {
                ay8912.setMaxAmplitude(21844);
            } else {
                ay8912.setMaxAmplitude(16384);
            }

            bufferSize = frameSize * 5;

            try {
                sdl.open(fmt, bufferSize);
                sdl.start();
                line = sdl;
            } catch (LineUnavailableException ex) {
                Logger.getLogger(Audio.class.getName()).log(Level.SEVERE, null, ex);
            }

            ay8912.setBufferChannels(ayBufA, ayBufB, ayBufC);
            ay8912.setAudioFreq(samplingFrequency);
            ay8912.startPlay();
            ay = ay8912;
        }
    }

    synchronized void close() {
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
            line = null;
        }
    }

    synchronized void updateAudio(int tstates, int value) {
        tstates = tstates - audiotstates;
        audiotstates += tstates;
        float time = tstates;

        synchronized (beeper) {
            if (time + timeRem > spf) {
                level += ((spf - timeRem) / spf) * value;
                time -= spf - timeRem;
                lastLevel = (lastLevel + level) >>> 1;
                beeper[bufp++] = lastLevel;
            } else {
                timeRem += time;
                level += (time / spf) * value;
                return;
            }

            while (time > spf) {
                lastLevel = (lastLevel + value) >>> 1;
                beeper[bufp++] = lastLevel;
                time -= spf;
            }
        }

        // calculamos el nivel de sonido de la parte residual de la muestra
        // para el pr�ximo cambio de estado
        level = (int) (value * (time / spf));
        timeRem = time;
    }

    private void flushBuffer(int len) {

        if (line != null) {
            int available = line.available();
            if (available < (frameSize >>> 1)) {
                return;
            }

            synchronized (buf) {
                line.write(buf, 0, len);
                if (available == bufferSize) {
                    line.write(buf, 0, len);
                }
            }
        }
    }

    synchronized public void flush() {
        bufp = level = 0;
        timeRem = 0.0f;
        if (line != null)
            line.flush();
    }

    synchronized public void endFrame() {

        if (bufp == 0)
            return;

        int ptr = 0;

        if (enabledAY) {
            bufp = ay.getSampleCount();
            ay.endFrame();
        }

        switch (soundMode) {
            case 1: // Stereo ABC
//                System.out.println("ABC");
                ptr = endFrameStereoABC();
                break;
            case 2: // Stereo ACB
//                System.out.println("ACB");
                ptr = endFrameStereoACB();
                break;
            case 3:
//                System.out.println("BAC");
                ptr = endFrameStereoBAC();
                break;
            default:
//                System.out.println("Mono");
                ptr = endFrameMono();
        }

        flushBuffer(ptr);
        bufp = 0;
        audiotstates -= spectrumModel.tstatesFrame;
    }

    private int endFrameMono() {

        int ptr = 0;
//        int ayCnt = ay.getSampleCount();
//        System.out.println(String.format("BeeperChg %d", beeperChg));

        // El c�digo est� repetido, lo que es correcto. Si no se hace as� habr�a
        // que meter la comprobaci�n de enabledAY dentro del bucle, lo que
        // har�a que en lugar de comprobarse una vez, se comprobara ciento.
        if (enabledAY) {
            int sample;
            for (int idx = 0; idx < bufp; idx++) {
                sample = -32700 + beeper[idx] + ayBufA[idx] + ayBufB[idx] + ayBufC[idx];
                buf[ptr++] = (byte) sample;
                buf[ptr++] = (byte)(sample >>> 8);
            }
        } else {
            for (int idx = 0; idx < bufp; idx++) {
                buf[ptr++] = (byte) beeper[idx];
                buf[ptr++] = (byte) (beeper[idx] >>> 8);
            }
        }
        return ptr;
    }

    private int endFrameStereoABC() {

        int ptr = 0;
//        int ayCnt = ay.getSampleCount();
//        System.out.println(String.format("BeeperChg %d", beeperChg));

        // El c�digo est� repetido, lo que es correcto. Si no se hace as� habr�a
        // que meter la comprobaci�n de enabledAY dentro del bucle, lo que
        // har�a que en lugar de comprobarse una vez, se comprobara ciento.
        if (enabledAY) {
            int sampleL, sampleR, center;
            for (int idx = 0; idx < bufp; idx++) {
                center = (int)(ayBufB[idx] * 0.7);
                sampleL = -32700 + beeper[idx] + ayBufA[idx] + center + ayBufC[idx] / 3;
                sampleR = -32700 + beeper[idx] + ayBufA[idx] / 3 + center + ayBufC[idx];
                buf[ptr++] = (byte) sampleL;
                buf[ptr++] = (byte)(sampleL >>> 8);
                buf[ptr++] = (byte) sampleR;
                buf[ptr++] = (byte)(sampleR >>> 8);
            }
        } else {
            byte lsb, msb;
            for (int idx = 0; idx < bufp; idx++) {
                lsb = (byte) beeper[idx];
                msb = (byte) (beeper[idx] >>> 8);
                buf[ptr++] = lsb;
                buf[ptr++] = msb;
                buf[ptr++] = lsb;
                buf[ptr++] = msb;
            }
        }
        return ptr;
    }

    private int endFrameStereoACB() {

        int ptr = 0;
//        int ayCnt = ay.getSampleCount();
//        System.out.println(String.format("BeeperChg %d", beeperChg));

        // El c�digo est� repetido, lo que es correcto. Si no se hace as� habr�a
        // que meter la comprobaci�n de enabledAY dentro del bucle, lo que
        // har�a que en lugar de comprobarse una vez, se comprobara ciento.
        if (enabledAY) {
            int sampleL, sampleR, center;
            for (int idx = 0; idx < bufp; idx++) {
                center = (int)(ayBufC[idx] * 0.7);
                sampleL = -32700 + beeper[idx] + ayBufA[idx] + center + ayBufB[idx] / 3;
                sampleR = -32700 + beeper[idx] + ayBufA[idx] / 3 + center + ayBufB[idx];
                buf[ptr++] = (byte) sampleL;
                buf[ptr++] = (byte)(sampleL >>> 8);
                buf[ptr++] = (byte) sampleR;
                buf[ptr++] = (byte)(sampleR >>> 8);
            }
        } else {
            byte lsb, msb;
            for (int idx = 0; idx < bufp; idx++) {
                lsb = (byte) beeper[idx];
                msb = (byte) (beeper[idx] >>> 8);
                buf[ptr++] = lsb;
                buf[ptr++] = msb;
                buf[ptr++] = lsb;
                buf[ptr++] = msb;
            }
        }
        return ptr;
    }

    private int endFrameStereoBAC() {

        int ptr = 0;
//        int ayCnt = ay.getSampleCount();
//        System.out.println(String.format("BeeperChg %d", beeperChg));

        // El c�digo est� repetido, lo que es correcto. Si no se hace as� habr�a
        // que meter la comprobaci�n de enabledAY dentro del bucle, lo que
        // har�a que en lugar de comprobarse una vez, se comprobara ciento.
        if (enabledAY) {
            int sampleL, sampleR, center;
            for (int idx = 0; idx < bufp; idx++) {
                center = (int)(ayBufA[idx] * 0.7);
                sampleL = -32700 + beeper[idx] + ayBufB[idx] + center + ayBufC[idx] / 3;
                sampleR = -32700 + beeper[idx] + ayBufB[idx] / 3 + center + ayBufC[idx];
                buf[ptr++] = (byte) sampleL;
                buf[ptr++] = (byte)(sampleL >>> 8);
                buf[ptr++] = (byte) sampleR;
                buf[ptr++] = (byte)(sampleR >>> 8);
            }
        } else {
            byte lsb, msb;
            for (int idx = 0; idx < bufp; idx++) {
                lsb = (byte) beeper[idx];
                msb = (byte) (beeper[idx] >>> 8);
                buf[ptr++] = lsb;
                buf[ptr++] = msb;
                buf[ptr++] = lsb;
                buf[ptr++] = msb;
            }
        }
        return ptr;
    }

    synchronized public void reset() {
        audiotstates = 0;
        bufp = 0;
        java.util.Arrays.fill(beeper, 0);
        java.util.Arrays.fill(buf, (byte)0);
    }
}
