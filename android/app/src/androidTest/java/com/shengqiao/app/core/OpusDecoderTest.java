package com.shengqiao.app.core;

import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class OpusDecoderTest {
    // pc/app/opus_codec.py, 128 kbps, application 2051, 12 frames of 659/440 Hz stereo.
    private static final String LOW_DELAY_128 =
            "AQz0fpfzVxy0cZA63VIzyj59DIXADpTL0q9WvfKoqNgXAUtIojaRV4FJALQVMvokCi5eWbj3WSV57UuOLzp1RbWcZgjCX1F+G5YQtuoMav9TlcKY"
            + "wjhtVhCjrnqSqcga8lLzSMNjqQyfmy5sL8v0WWXbiCexWPT+VLHLw+R+vxjm1cfHGhonXsK2WHs4gAAAAAAAAAAAAAAANTn/0JdJAA7t6qHgpwpM"
            + "obQTmNAERNT2r+Wa+56IiEkkPSbxZju0W9l7lpXnEXDtVfPEfhs5pwyMn9TQcMYvhYSfNa8QsMgVkCaL85AxN5HfXakqpA7Q0W6HaMlduhdLXXoX"
            + "9GbZ0hqENXPQmPwfnr8nKLBiAKr0yEbg77m5syP7nwiGjll9D52ilLwtuKfHTsaYxCQkJYi8Yp2ntZ6t1uGxg9gw9lpx40z2Cwd7NpYfhWfjxH8/"
            + "+huzxlPrW0a2OsNzuFy1uNqPWe4IEiSmTCQnklVL0/xiMGvcYAyrotjXUnsS0J9m7XWmrXXa6GgB76cHY/DFFFcUFyF/GOhFelrn3S0V2G63kQ0d"
            + "LrOstHt9m3R9tPiFduZ5jQb4IbwrWgCW9Md8rkQUaHuXWJzWgBRNucBxT3sjxOPhkH4dKEHAxrKnU/JprTw4mrYtYKUdI0mk4Hf9qHEAKx0vIyYd"
            + "IZGowZ0oH+5DlA/efn44OLStLkGRcCliNAb7w+E8gm6EJwvws+mjanrEDTjIA0LmY4BdoJebU6zW13gcG7uEGMx3GGuCSpDA9rvpBS4sAGaQRbET"
            + "49wwev9bALj0xjC4E9BX9c/wkzRM1s125Pfa8mX1i5JnuxULGk3Wse+/6mWR9LRWoLKU95yPz68KPDMjvgCO3B6hJS3geGCTFLM/+SEiejNxBvWV"
            + "kExCwTKQphLoyUdHjXm0yVzFa5hC7jDbq7Br2qBnTGfaRJmEYm0Yoi7xvDSzJ+NDBXGBQtbdap7DNj/2keG3NJtDEB4dlPoq3PN4+Wfm59ax6j8K"
            + "gNi5ivWBv+skwtXtj8f6a3U8JzW6Nc9bALn0wlxkGRB4Fn464GC0vp+Q9fyo+8CzUpspwsk8vRjau6R7uWz6H+A980FmJ9Q8B1MRONF2pNUBJHEE"
            + "JSbim+61MDvtLRKl1pBaVzrG6ov97MlM7endqS5WgCz6opj2l1eEchSasboVJ8KV2lFFLj6aSOKtB5tdEmrXDD+vGhCPHgQ03pQ1LqOnKeuA03yI"
            + "kMcIaTBkhLyZOYjobdUT7M01+cDfTvLWmwFmb8EvtrMzDz+Pm9mrNQw3WwCp9MJh0GRrvCgenUxbObntSqDIvUcDHwPxDQu0tIKXxRNglsaEw01a"
            + "m29eHBA0NSmnXaa3dYXXSvmL4oCrAYVxkD9+SeoOcaaKI0HU7gFSAHE1hjaPXDR3RRC0kHKr8Fmw8nb7171FQOWtSqdCJJ4YmX5qD/QGqm9pdyIx"
            + "ahFecib6AsYL0Ju6n0eEzCjXcN51Cjodpe881IQiMwTLWd2Tw5olMmyVecePXACh9MLDhaj3qGc07dQiwRrMsl6Q2wD/291U6h3hxUecyi6G0Q+N"
            + "uqotwgmQsLcazg7sSPHdP1c19zhyc72M6v3BQK+YGmJojLZQ8xKH3yHFGcBdUQ9Wd1Ee4QKpG2fW+mnIZVAETT7wgc3QFi4B6P8lXC35z952Hrvi"
            + "llmsTYBScq70RK9XbAdlkFmTIvQ2gVLvdy3OsqSnopDRB7IjTV4Oo1wAofTCmDE1ruf+acd8+/254ZNX1lm5Afw/GKiwOsFO2Z2UWqSyCRs5/lMY"
            + "x+rgn4z095A6igeJXS3VTKMBGfSLEfiapKJHcLsR3sXC8YiFCRKJKqB/Q8FzvIT9lB87RHVfeVz36NmfZn5sfgq/c/71wuoaJ9vsZLq3T+wEQi+2"
            + "FM/nX4fii480Y++CCnKNCWRYLAcwM/Cseznm4hfBz3gLJ29bAKH0xgSZzRWbFqIXs66GBL42H12kkuS14LEv7II9ExdL1OJ24nuUY904Oqw/UNg9"
            + "QZ7XA2adwhHYHBFsadvXsaPrFRdsPd6cxkTUMPXQeYcE3V4vPdiI1osav2llx8G5CgANIO1ntm0f3p4dvmOjG/5d4bKcfMzVRV2ANlsk+xIzwzm6"
            + "ZEy6utEmm94ljW7a4D5D/rAz172g302xV3Nfi8TvWwCh9MIncTAk00OujdkAQhuXV+syotGdGQnO85ZQjtaU4IcufN0w2wYb/J2R3W2nDkGRw5jD"
            + "OHikEYgmkdso4W3R6FWf85F7w9WE0z20KyuzqQkhWbgLdky4xijqbpmyBwMibNuRTIlzkM+UGHRxaf5YKLew+jhD43/liIL4vnTn1TBXMWj6f+jw"
            + "hz4wECPH12zDy1kMpfAS6qN8PPDj56sjC1sAofTB/y9E/LKsdRGWjRRksyTihFWM4I3Fluzk8hUir+E2LZ7i3RkVicaLoZEbpoYQL7L5m6O+UWu1"
            + "hKCQU9Usr1SjbH4Etkdb9vvoIdeqiJWmZ9suKKCJxQ3Esl6ywbQAXKuU7U7gwv8WtSZnYkauq5ZVtkXm9fUmm4eKT+vKFLbdUhBJxm7pb9DosfSC"
            + "zxePXP/+IpXbWhyVZsUhNLVUSFNbAKH0wtK3hsNtBxEh3CDWyDNuY3xgXpLpk18cW1Ng1mqhp4uzTJALOnbfceupoklTm1wZ5C1HCn9BW6AVwDSN"
            + "5veYaG8yxbOS0EOmlobDBWMcvFoZsVrjl3NpQAIHAfyRNnMhtp7A62IkSkgEYabefYXRshDeQwmUOKWXcw55rur74CZKfJ4z9cSGuAiGI9EN3Zcd"
            + "zv+DpqSjbgA5cWOgAr5jXA==";

    @Test
    public void lowDelayPcPacketsPreserveBothChannels() throws IOException {
        try (OpusDecoder decoder = new OpusDecoder()) {
            byte[] last = null;
            List<byte[]> packets = parsePackets(LOW_DELAY_128);
            assertEquals(12, packets.size());
            for (byte[] packet : packets) last = decoder.decode(packet);
            double left = amplitude(last, 0, 659);
            double right = amplitude(last, 1, 440);
            assertTrue(left > 8000 && left < 10000);
            assertTrue(right > 8000 && right < 10000);
            assertTrue(amplitude(last, 0, 440) < 2000);
            assertTrue(amplitude(last, 1, 659) < 2000);
        }
    }

    private static double amplitude(byte[] packet, int channel, int frequency) {
        double cosine = 0;
        double sine = 0;
        for (int i = 0; i < 480; i++) {
            double phase = i * 2 * Math.PI * frequency / 48000;
            int sample = AudioPacket.pcm16(packet, 8 + i * 4 + channel * 2);
            cosine += sample * Math.cos(phase);
            sine += sample * Math.sin(phase);
        }
        return Math.hypot(cosine, sine) / 240;
    }

    @Test
    public void bundledLibopusDecodesAllComputerBitrates() throws IOException {
        assertTrue("内置版本：" + OpusDecoder.libraryVersion(),
                OpusDecoder.libraryVersion().startsWith("libopus 1.5.2"));
        for (Fixture fixture : fixtures()) {
            List<byte[]> packets = encodedPackets(fixture.bitrate);
            assertEquals(24, packets.size());
            byte[] decoded = null;
            try (OpusDecoder decoder = new OpusDecoder()) {
                for (byte[] packet : packets) {
                    decoded = decoder.decode(packet).clone();
                    assertEquals(1928, decoded.length);
                }
            }
            byte[] expected = Base64.decode(fixture.expected, Base64.DEFAULT);
            double error = 0;
            double energy = 0;
            for (int i = 0; i < expected.length; i += 2) {
                int actualSample = AudioPacket.pcm16(decoded, i + 8);
                int expectedSample = AudioPacket.pcm16(expected, i);
                double difference = actualSample - expectedSample;
                error += difference * difference;
                energy += (double) expectedSample * expectedSample;
            }
            assertTrue("音频能量异常，码率：" + fixture.bitrate, energy > 1.0e9);
            assertTrue("解码结果偏差过大，码率：" + fixture.bitrate,
                    error / energy < 0.01);
        }
    }

    @Test
    public void lossConcealmentProducesOneFrameAndDecoderRecovers() throws IOException {
        List<byte[]> packets = encodedPackets(128);
        try (OpusDecoder decoder = new OpusDecoder()) {
            for (int i = 0; i < 10; i++) decoder.decode(packets.get(i));
            byte[] concealed = decoder.concealLoss();
            assertEquals(1928, concealed.length);
            long energy = 0;
            for (int i = 8; i < concealed.length; i += 2) {
                int sample = AudioPacket.pcm16(concealed, i);
                energy += (long) sample * sample;
            }
            assertTrue(energy > 0);
            assertEquals(1928, decoder.decode(packets.get(11)).length);
        }
    }

    @Test
    public void invalidPacketThrowsAndCloseIsIdempotent() {
        OpusDecoder decoder = new OpusDecoder();
        byte[] invalid = {3, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff};
        assertThrows(IllegalStateException.class, () -> decoder.decode(invalid));
        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, () -> decoder.decode(invalid));
    }

    public static List<byte[]> encodedPackets(int bitrate) throws IOException {
        for (Fixture fixture : fixtures()) {
            if (fixture.bitrate != bitrate) {
                continue;
            }
            return parsePackets(fixture.encoded);
        }
        throw new IllegalArgumentException("没有测试码率：" + bitrate);
    }

    private static List<byte[]> parsePackets(String encoded) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(
                Base64.decode(encoded, Base64.DEFAULT)));
        List<byte[]> packets = new ArrayList<>();
        while (input.available() > 0) {
            byte[] payload = new byte[input.readUnsignedShort()];
            input.readFully(payload);
            byte[] packet = new byte[8 + payload.length];
            packet[0] = 3;
            packet[4] = (byte) packets.size();
            System.arraycopy(payload, 0, packet, 8, payload.length);
            packets.add(packet);
        }
        return packets;
    }

    private static List<Fixture> fixtures() {
        List<Fixture> fixtures = new ArrayList<>();
        fixtures.add(new Fixture(64,
                "AEl0rLD3PX6VaVGBJo3k3MVgXsXePP2XP0kRI2YpNA9hZCaFzAIZkt/3CrV7/dnC0Z4YZZlgRwsAWtFCOV5UXogXPUDNGcrRmjZR" +
                "AGR0rLFUBEmOTajAQtFXmDQrD5JB2jrsleR/8zErRRL5LVsM2dKE8wnQvvgw0l157fpZn5X4By7Z7k9zLAs3TO9D2+k3GsykzuVJ" +
                "bweid8Jzalzxov9XejrQDJFnUnjTUGiFpnq1AF10rLE5tji2HaHqcdbpbBQVGQ//z6TCUAXPntAUdfvrjS/Dt16A/2g9XG83ywYI" +
                "IMzIIeVisWFOm/TKoq7TSZ4MMdF48D77c4z4203IRsFLWf5ERt/CyjVs70gaK8wAZnSssTB5JK06jkQOLQIoNfXSWdLmYa/SU16S" +
                "fn5vM0aP4e7jnE+R1LiwwMCc2KeR3dn7X9gJXG24b14L5l6Mk1iZj/UXIxzXCqYWlcLDICuFkgKtoVuCoRj2bNPnDd5S3fosfYse" +
                "uwBndKyxMHkkrTqORA2dujgOUktH899CFOwPuDyVAjrtNOUMBo3g3rMXACYGMHs3DDkYEmTGJmu53d0g1I1SIKbZYly1zkAlnVYH" +
                "WPYnaa36GBMuicv1i1XJVIAZG2Nfx2YlNxrZDQaL7QBmdKyxMHkkrTqORA0Ol1QT+mENzGsP1OMhLTU534MDBm889b3hniQF3MRH" +
                "p8eetL2XNKUiI+WDo2nnJgfKUIgu+Omi/hnXJMIiyuLT1GPnVa+MUNQNU54qMoM100vahBiZjRZU0Z/rAGN0rLEweSStOo5EDi0v" +
                "agb+nvkD0zCq+RjBXsl/0qo5eV1BHD2g8CICnDMA3oedWycyx81Lkl3r8qbdM4D0olKLGc7VDRZokjw2v6mHRL5wuTx/NLCQN1m4" +
                "R9F5XAHc8+jv7v4AZXSssTB5JK06jkQOvHs3bekpJWLlSH/mA8SzqIv7O6PHVYB/TNq0CGBO0K5u0BAg9ELTvYPTfZbias2/YUHC" +
                "0PmwF55GkD/Rm2lF5VkNqMTQeSJMnuTG0F8egKOWwzAbdozyT8qmAGl0rLEweSStOo5EDi1MS1qnz1W+oSW0VLMtK6KXxmJx4XmD" +
                "LWim0Kvien2jE/noq3AOhhECwODjn2ApaEDbwKs53OYVkqAILTbI9kFjElR/dnQ6xhbziwbf4YFsCRlakFNXnQDvk1fIIyMAbHSs" +
                "sTB5JK06jkQNnhFFt6V+W8ewnWEIelVoR1AWlAvgaqF8CmTWQdj8lFL3lhxOMaZxIZmrsHM8yz8fI8aSQfA/lA1tqenARVb9HI3a" +
                "bZAJz1itQAGJUGPihLB4BTStjBBxLgoPCNCEDTeu/gBndKyxMHkkrTqORA2d42BoaJB2U3/wZbVlfc3EVKppVL5gVQcJnuKLfmsD" +
                "V8cqW9QB+cq+QUkMrnA39wD67EjtDMFWHinGTrUGOcmBnkbBULG3paBQn6RftslkJRpjSBWlGRTMmT8O5wBkdKyxMHkkrTqORA2C" +
                "wK+yTE/iU3VzBb9pf31/xI9oY6FJlGI8zynsKJgsTqccN9Kcqv4bY1bgMjMfrJWwKDiz8bCHqPVNaHAqZpGh1DN1cBP9tTh/uDQ7" +
                "AgSTcWyK7YKm+Pd6twBmdKyxMHkkrYnZomjxlwo1ziYfCPVc4eJMk9ZdmqMtqCPGp8RRXHbIx3QIs41bDsJfZuNE5Yu1yu70L659" +
                "g02DitcoQaaXvMdpZcBu66Q1KaQ8k2s5Wyxmy9kyMDtk2LrGs9CbTgriAGV0rLEweSStidmiafsCBZ9vqtQMzGiWCuAhJp5uEpdb" +
                "viaiCfjCI8yduePd5CBQXa/fdNuqSk7XXEQYQBjjDgoISuE+15LA3WMtmIAeqxuwoIn8nuyIQ8YRYH9cIO2sadahqsGoyABjdKyx" +
                "MHkkrYnZomn7hgT01MaG91FU4sUUwiy3bzuBNwaxXvVVnb14UmAas8lnf0VTpJokx/uIDwzPYUdIf1z6Y6VHQ6V70VkvLKIoMWHA" +
                "kwbTgae31Tl2pCtCHp80K6d0Uk79AGd0rLEweSStidmiaPHs0D1lGwfUdwMPVG+Kz73dvIfshkTno9/vBJGkWjm6DpTeSArESYsW" +
                "FX76unLK0c3H9kTXgn2FLCcTC3tdHkz265c30dRN3dgyaiNAbPo39nx5NRcNI87p2e/+AGV0rLEweSStidmiackHe2+zXJlLcR89" +
                "4CALBPYQ3hTmAGaYm8lg+8uUlqbwtxbJo67THDVYk85eE9c7Gas8i9vzpl1afVDmV5WmpJ1azUfyspNC2NCLuRR73lbuKCiWi/BW" +
                "SfaK/wBfdKyxMHkkrYnZomjxoGWUFX63irMidstUUYi8J3QOVB7ZutlAaIKnXlPrlSNO/+N1CnlnOVgGhCWqTVPpihSARYGT11Gg" +
                "egtL49fKlYs5KyXZXrPXaCqKeXn+7uMaWroAZHSssTB5JK06jkQNnbpcrtATXz7Chx15CBleUGd35/Koc3A1pso9cBd4jk1okMFU" +
                "tzZ+rFiGfDjz2jqnVdMGG5sNmhrz0972MqAbkd98d3lPNL4bFvDJ0nE0ewanGo3+vreqr+8AVUSssS5Tz60zxlebETJNWzQAGd8N" +
                "ai5F27yDnQ8KIUMhQg8BUkfFHAn0uIKUTD1rRPjG+/v1a+N0t04a0jJB4O3uZnRpJaKrB5N2rG8lslMCkmPWyHAAVUSssS5Tz60z" +
                "xlea7kCKlMPb/ZcJwm1sbd9H2L88eT3IIClK52MGjAGcEQnwfnkdMJqY1ZsDEj02pgwebw7CVhgz+NyZzroo5QS9QJt4sJwBd1L+" +
                "hPsAVESssS5Tz60zxlea+eJUYr9qzF30lS1Zyl5bVksXnLIh8QcTG/xSZ7Z26/zng2Ugbb6zfsKpjbswOCeqZijeVWXaTYpDpO7g" +
                "20FwdG/KDsknNnpIIABWRKyxLlPPrTPGV5sFh4bt88RsokcZRMSkypGJR/2n+XtwhPSCv6PQAeFA89e7RaZYj3Uvrg0vna6dJMM5" +
                "htnixhPe9PRKGQgBJ2tCjHHiZb9Xg5azeqAAUESssS5Tz60zxlea7j0mZn+LMmNWD9PjSRSJ1rnNK3ijccub7ekcbr50V0OvqEdj" +
                "mjmE5QS7JWSD4PD6gopn47DZV12JFLamG6ZGKu53+jJA",
                "XuW7EQbqfRUQ724YZvRvGvH5bxuV/2IbOgVHGsYKKxgeECAVLBVCEd8ZuAwjHq8H5CFaAhMl7fyeJ6D3eymo8qYqNO4cK3Pq2SqI" +
                "59wpjuUkKJbktCWs5JsizOXrHu/nuBoA6xcW4O4aEWjz0gtt+FIGv/22ACcDFftvCIj1Yw0r8M8RFeuHFVrmbBgR4mUaU95gGy3b" +
                "Uhus2Dca2dYXGLTVBRVA1SERfdWWDG3WkQcP2EUCYdri/FjdnPfo4KryAOU97pDpfuqB7pTnv/Oa5TT5ouTC/rfkUgTZ5c4J+ecf" +
                "DwHrMBTV7vEYUPNKHUj4JyGP/XUk8gIjJzoIJSkvDXQqoREMK2IV6SpNGAsqSxpzKEkbIyY+GyojLBqUHxsYdBseFd4WURHnEdkM" +
                "pQzgBy4HmAKZATb9/vvv93T2+fIT8YTu8uu76iTnwOe/4q7l2t6e5IXbm+TS2KnlzdbA537Vx+rq1J3uFNUa8/vVEvia11b96dmx" +
                "Atvc8Qdj4OEMc+RSEfvoHBXq7R4YK/M5Gqb4VRs9/mEb1QNZGlgJSxivDk4VxhOFEYsYFw3nHCoIxiDrAhYkh/3HJjr40Cg68ywq" +
                "ue7UKuXqxCrl5/kp0eV0KL/kOCa55FEjveXQH8DnxBux6kQXcu5kEuLyOA3U99EHG/1IAn4Cs/zFByn3uwzD8S0Rm+zzFMjn7Rdk" +
                "4wMag98eGzbcMRuI2TUahNcwGCrWNxV91WkRgtXuDDvW9wep17cCydli/ZDcJ/jv3zzz2uPQ7kDoC+sP7RToNPIL5pj3AuUc/QPl" +
                "qQIN5iYIFuh/DQjroBLG7nYXLvPpGxf44x9R/VUjqQIvJugHZijZDPMpSRHKKgoV5Cr4F0Eq+hniKAAbziYAGw4k+hmwIPQXwxz/" +
                "FFkYNxGFE8MMWw7PB+8IjwJbAzj9tf3+9xn4EPOi8p3uZu3S6nro0efx47zl4t+q5F7cpeR62a7lRde858nVuuoJ1YvuBtUJ87/V" +
                "Cfgx11j9VtnBAiPcDwiL3wwNfuOIEejnWxW37F8Y1/F2GjT3ixu2/JEbRQKHGsoHexgrDYEVURK5ESsXRw2gG1MInR8LAxAjpP3r" +
                "JVH4IChJ86opve6CKtzqpSrL5xMqqOXMKIfkzyZ05CUkbOXYIGTn9xxL6pcYBO7OE3Dysg5k91gJrPzUAxMCPf5iB6n4ZQw18+wQ" +
                "/O3LFBPp4BeT5AsajeA0GxDdUBss2l4a7tdmGFvWgBV61ckRUNVlDdzVfggg10QDGdnr/b3bqfj+3rLzz+I57yHnZOvg61no9vA2" +
                "5k/2D+XP+/HkXQHh5eIG0edFDK/qcRFf7lQWvPLXGp735h7X/HIiMwJsJXkHxydzDHop7hB9Kr0UyCq9F1cq1RkoKfEaQycFG7Ek" +
                "ERqAIRwYwB04FYEZghHVFCANyw8+CHcKCAPwBLL9T/9x+K35ePMm9P3u1O4r683pJegq5Qnm/uDp5Fnd0eRM2sPl6Ne65zTWo+o6" +
                "1WDu/9TL8oDVtve51vL8pthKAj7bjQd23okMQuIKEY7m4hRF6+wXVPAKGqb1LBsl+0gbvQBcGlQGcRjMC5cVDRHnEQEWiA2TGqgI" +
                "sB50A0giHf5IJdT4oifN808pNu9LKkTrjyog6B0q6OX1KLTkGyeL5Joka+WAIU3n2B0i6rIZzu0cFS7yJhAV9+YKUPx1BasB7v/x" +
                "Bmf68Qv69HoQvO9hFMLqghcl5r4Z++H9GlXeNRtB22QazNiSGP3WzxXf1TYSd9XpDcfVFAnO1ugDiNiZ/u7aWvn23V/0leHZ773l" +
                "7+tY6szoU++O5pX0SeUF+gvljf/X5RcFoueICl/qyQ/y7cgUOPJuGQf3qB0u/GgheQGdJLMGNSeoCyQpKBBfKgUU4CobF6MqThmq" +
                "KYca9ie5GpAl5RmGIhIY5B5RFb0avhEmFnsNMRGzCPILlgOABlf+8QAq+V37P/Td9cjvh/Dv63Pr1ui55p/mbeJf5aDeI+Vl2/Ll" +
                "y9jE59vWieqg1SfuHtV48ljVUPdN1nv8+tfHAVLaAAdN3fQL3eB0EPbkVhSE6XMXeO6tGbrz7Roy+SQbx/5OGmMEcxjrCaYVSA8D" +
                "EmQUsQ0lGdsIdR2vA0YhXP6GJBX5JycP9CIpeu9rKojr+Sph6MoqIubeKd/kOiil5OglduX2Ik3nbR8a6lobvu3QFhTy4RHw9qIM" +
                "J/wtB4UBnAHXBgP85Qt59ngQFfFiFPHrghco574Z0eICG/7eRBu923saG9mpGB/X4xXU1UYSQdX7DWbVLQlC1ggE0te8/hDaevn1" +
                "3Hf0duDo74Tk+esJ6dHo7+2M5iDzP+WG+PjkCv685ZcDgucSCTnqZQ7F7XoTAPI9GMT2mhzh+4EgKAHeI2IGnyZbC7ko4w8jKskT" +
                "1CrnFskqJBkDKmsagiiuGlAm7xl2IzMYAiCHFQIcBBKGF8wNohIICW4N6gMDCKn+eAJ4+eT8jPRd9xLw9/E07MXsFenj59TmaeOK" +
                "5WrfQ+X82wXmL9nL5w3Xgeqe1Q/u7dRT8vvUH/fJ1UT8U9ePAY/ZzAZw3MUL"));
        fixtures.add(new Fixture(128,
                "AQj0tJKOniIVUCoSCmQP2lauMh4cbZhow1/C7pvwqPugPBzSODsZHDCrzaBaaj0oa8BgCnOBkUz8bj1t1xpPXfCCRw364Lm6Rsjo" +
                "qh+Y5K1jOmtTUL7fsjQdyu0+jvHt4hD6SArMOugmBuld1Mk72VDmN6fVfVog5X2w0uzl4AdRVVDWIAAAAByCXyp8/+mUkLp4UNJ4" +
                "MXUOWXUOWvo85N+f2gzM7RDn0PDtURDn0JJJ173qkSnR3OnBDskSmOA7bcfylEx8QmmlEgJkV5YzWtty8FYBBXZ4XaHjgPKuLMX5" +
                "f5AALdaX0aIxFH/7Wz3OwHzII1rIge+tNqts9w23trH3GLuIj/M/PSEAn/SzikYOQm7x65KXP9UMMNIwAz62Vy084zsqZv5pIbxc" +
                "cQzRxuYmDTEOKRKOBScEL1JMtC6qTTET0HpoSWCg8fQJjoMneTkOW0kNPKrvgOp5U87ITq6leyd4m1qKBI23lISoPmYYlg5WkuN/" +
                "9WYciAUuUPwoieSl7m7S9esTXgrcKsqcvZu8nL7DCF9Hi0o9L+ySgciIU1n2q5omeC/xoQCb9LJLMmaPuzU6qJWZ49LguPSguLyc" +
                "2VmoS3FKm6XUhdI2s26Bu0ACq2dzKguvElleYE/rftKk60F+vXjTidNfIkKP8NfPHNxsnq9fYok7LI610YPB/ekA1W/Yc4VHd6X2" +
                "ARbvH+P+8BtfOGuY6ivNSoAslZYvXrza0VDMI1YKVIqdfXpy6vEkDn+pjtNSNKqlOrQEZIUZjO6iFaEAzfSxUljOlY4n/uOBHIE4" +
                "mc/nMJHIKTJkyPxjb5GiDy/oSIYIiDyYOhN5n0lh9kCd230DRwwq1nZKGf1J9TPRAqnj6glttnFEC06L8wbzTE1wPX7ue054Lbfn" +
                "hZz19HmbiSxwyGVyx/qjafmHE1oMX33ojWHTgwhJ1Omkb6TnrEtjH2DcxFrfm+TAa52TB9xA776A2mrlIGbzrsSijfDtkbNDtnDl" +
                "S0xqBTKeamoSgAa1zJpz6tbH0+i0V8MV9ax/5YV/0T7uJFBkKCjnxaEAtvSxR+gu56oFlw1mr9sdE1R3Flr69evXmLbv9CMcCrzi" +
                "7TmvBhPjnh/4FNnwvsdFb2IMDBGBA3QJbCcX4yVD4wEBzdEDr3JgKW2UkY15g0whx8hB+hgy/UMXAYXRk+dPqb71dYYx4sHVeg9m" +
                "Ih0TWYeTSuEsBe9bGwSNttli3nyhP+rGMT5JALCxacjoO+0gQj725uit7zKyWezfY6Zad322bUbOHTdN7iHlFZ+7E0kVhl7QRdmh" +
                "AKH0sUfoLtyqWH7HiDPDsg5o6HAmChQoTrdx1v373hi6AvXxybMrPz4xceRtFJH/xJj4W2nSY6/ccRHrwWfEsCZkaCjScY2WN1Pg" +
                "ptyt2PdbdrsIoUgrXxmEMa25wF1TwkZL9pyfik6GvcNfNXWFK1DjJXhY9FKbuIqGxsDdOJNt51Ef3/F7FOt3p4Fytcfbjx/c//1W" +
                "I5XUAyKw5MuNk4UnoQCh9LFJp8zEASv4RoaRiDrC+t6xGZw4cOGl8TvoAe3EdLUtfsd0QhUZxIkn/c5BgdxjaQlv1coIrb2joG3x" +
                "yX7MUs0ljd/0R2CRXQUDYgD2ZK5vu9uf7rM4nCYVU033osQydp29ZVjP5FxjmuFi9Slc4yV5/KMyhvjSGJwF7Tz6CyoggfH4p2kP" +
                "dpnvVWrDgFcP/z5t7iOYU/pKl6OJjtV4p6EAofSxScSiLDKbTSfxUIuxSPaCggbzRo0aGRGk+sr5Len7k1iqY8jWGmbWl2BvewwD" +
                "ixsu6eV1ZaADNcX75rlzas4Z1hcbMfMNkqvunC2Asp+kVCe3zqlFEE7h/YjmyKAyUg6+We7hrSPe+qq9Sle3OPC5g7TnbPA/htlX" +
                "czMM5ZzeU7z9uO6YJbuNt1HRCKdwQ5mZHN9iOUMvmIeRFePNO4GhAKH0skehb82vQVfBLM0vFCCdc6MOeKFChPmSPkg+uWyQJBcP" +
                "uuhhtK+zHA4Ys8affd+sJWme3YH2tJuRJUm/MTK0pDCUNdWvMQEnTRRLNRhvrMxoMhHWsGgrE9wwwk7XRKNiGdjbWe+OEbsvZp5B" +
                "jK7oWpxSBI8NPQimb/Pn37vqx06cTGeK6MfP1EVXYdWgUWsWett1kOUEfBeVBN2OE9TzoQCh9LJHqsScH67gcp/GWpvYINMphXFf" +
                "Pnz2Cv1fyWZGYv1PAicCJG6esLGJVxmlk3M/VtDj9YSCE+ATDj1KU5yTNzBEOa4bqeItsuvCW5iHuQS+aDOi07JBV5dGOQ0Kpjyc" +
                "UJ7bETog8hiFwEBE5AUies2ugxldmKDN19LkW8zJUUl+HgaDcnU2I2lx/YQKSRbZaYVF7kIbxd9aPySvibRrUaEAofSyR6rFzRYQ" +
                "Qn9DB2k0ZGrp9e+b6ylSpUmyOP1RxsoIqYBGua5ek/3Iq1NL6wWaqlHamKxf279jn5LwWG8hor/Rv236lvPW16ORpP4g3TZBLys/" +
                "/gN0l8s0CRfxU51NyY4cTCivdwR6R4JiQRDPGPHX6Jd1ok7EhtBv+NmTpAyaRa0Rr4tpzngTHT7aMqdHAsPOxfZBl2XOLDAEP4jy" +
                "+uWhAKH0skiunIlKPc4nbws2bY8aJm2p06dM5mc9RH2ZyMt4xHlGEd4Jf9G5n2EgpknWHte4I8Gfzm+kWBb+fJ2iKZVh9jmuVXp5" +
                "+8DB0vvX5jXwn07+889lJDpXZCIUQjokuH8URcSCI8XEBlM5YIrqF1FJZtH9EHJiRY1oHMhqGE0RxKWzL9grxw6hlZ4+xU6p/TCg" +
                "Am32QZcFtaYwhEWD1IU9oQCh9LFIMtylTOSN0DXpwUC7ydSDeYYtWrVmCKfnYrYUXpIuqopLzIRRu86pKyeH3iRmlImLJOv0hV2y" +
                "ghvQXXAK9JLbKxQx8QbKbX3O5VJCQrD1mnrmCOo+kMeXrLbe8ePLaxIASpa54+e0w9ZM4fHaJmbR/RByUwrS6B1OOKd5NYvn1/4U" +
                "Grn76vVresR3keUBexRsVkGaZY9eSMPJjBQ5RaEAofSxSDLdF8+ZiC7Ua/f1hTaGrI9u3btdLkS3BjlgD76UlvSAVOzZUxDmM8HP" +
                "NfhmPmX1oSOKHmZPIP8SCVZjsU+DMw/WrX+GfIxa0h8223YY7lj0MFoZkf+d9jHnT8ehsem6gye9uVkD7GAmsJDjH1Fm0f9XEvh0" +
                "W+payPnIezPpDQhaQWMaTEEaI2rZgnhljWCiTe5BFbJRknDDS4QSPS+hAKH0sUgy3ScqcDQrK3fJvJPP/i8vN3v3796hpAW7lGg0" +
                "i//s8jHKteSsNpiXPBm18QAwRpUYMps4O/fGcBif5BxbGpw4go99cytqTzkVeTT1q1NbBSqyetm0FJHZI40RyoB4e8M53NZEFMUv" +
                "cHhij1FRZr9/VwFXXelqWnR5GPnl+4uFlX+Kfx8R12SqSIQT/js75EV2A5b0+wKY5MuMFT0xoQCh9LFIMt2X8iS4HA4RScycz1mP" +
                "eOXLly5vvl2BzUgDEpigSH46qqr5yX4TUlzorOedeMGmWnoaEy3nG+v9g/3aqmObv8z+XGvdF05Y7UtWaOAlAoFOJfjAkgXtHaIy" +
                "el9uzPKP1K8ogtMHVYNR6z34Y/SyTpFQ0ku1UDIHHqfG9pdqC5grXe/Eh4dEQ4bH5F6WYh+Xjd+ZdTP2wKNJi9M9L6EAofSxSDLd" +
                "F8+ZiC8gZv/Ct4BKTFvMmTJk05LZQzQvJhcBmXoPfLAWjmsmsPA6quf47ElFKUZR1b/cv7UqVAeChTRajvEQLqXsJfXJknkN2+kT" +
                "sBnuzzUZuXQYgyJYZMWha+Vdk9a7au6bs88FRIEwKx0hLE3XTfz6+NaUhD8L2EXV3MF12kj96DyGbQu2RgcXB/Rxl1iBFLYUSuCD" +
                "xZu0Oq2hAKH0sUgy3RfQAeHTesn6dDsFRUW8wJEiRIkoDtSEXKpD9+Pa+mp3aRtajwwy29feQ1ycyavbE+gpxicma8v3pfap4Le8" +
                "T0hZokFZFqclhWck6w0QlnztTwzH5RR0/mXTWYQOhl0zIt44hCGGfpAHlgMbxaxN1/z88q1Wlc3dJ/lFIdmRwStkLdPsjk0L39fn" +
                "NFFTu3esnZe35Or4BD+bMzUxoQCh9LFIMslYHGnfiaCjyzDBkjNu2XLly5fH9iTdKVaJGFeXdgN7HOTYDhTjRdpsl8OnFvjgCWK7" +
                "GgSSl2sqWvVpmjE7YJEpiXOMDnpQhKm0evRAIqG1kbQpA81u3vYSdjviL8P1N0x9IGWKS7CrT+P3MJj1q665pDSwUg32RQkyr4VL" +
                "B/VXg99NQqRFC/XgEVruR8eNqp8YGPUdH6O3krMusaEAofSxSDLJWBxp34mNs5NpymUJCB+PHjx5V5/QCk1V8iRfZ1CNpJNE0IZ7" +
                "TjU451JZ17m8mMPg7I3qYZHeR7os0xova3FVi6NGsTdxgKI9YNb5ZHFYJ/0RA4PBzTFI3huJjKOLSEyT3DcbEFfkOpsw4pgaCP14" +
                "FnG5HUgyFdqCMhfhuYVT3MDc+9j+iQnoUUktuaYnFUp/lDr9ny7jK5nTJU+hAKH0sUgyyVgcad+PdGadwA+JSHWJQoUKFiea10da" +
                "vJshY9msHKBIdMRL+ufYmMbCjRWzDY0uXpRWzQdp3UJHC+jhARYU240PvvmojgpAdRpoYDoc940lgqP/sN6S4H8AmH49xBM6NaXi" +
                "KTdJY3NOdZU5v9R69AiF3d1Fn9MZPs5H1whe87IJRPBvgB0Jz7F6Dwdkx3eqX5ha/eM95B2gshsroQCh9LFH6C7cqmlBjNNplq5E" +
                "XRUgYUPHjx49IRU1NlDSGmvpXzgWxfw4HRU7g7XYnt2rGmbyefaJwP5V/fpq2SFZ0parT3n1A/0tZNrfY/rg7VcrbF0krJCVFg60" +
                "9cEWpJpQAwXIGbEuakhkJ4Z0/y/UevIcheFKum0TMTgdJSfBcRWmMtNpH1PYRMEB1N1mBRxYRNn3Kn+V2wYjSeNbrxUPTaEAofSx" +
                "R+gu3KppQYrP85NvSwbPP29evXr2e9fsRIpW1OMOSYUxTR27w1KmiXLPVgatMHzGnu77ssWTZemNKu2qZOuz6WOIvPh5Z6NzSUzi" +
                "pX2HxBOGexx2dAzHSrb6bo1gsbHeMlNi4RI8xwk9CC/9R69qP89B6tO3itXcbLm5We6UVKcKa0fHyFU6NLHGJjN47zKZtah/ldwG" +
                "Y1iDxayQgMehAKH0sUmnzMQBLXfLrZmRWveNy5k1yVKlSpdGutcU4YSieWCq/tvkpQvcj8A9o5cpPgH5pd2vDu5V+7VYgwvb/m10" +
                "PyJk6Up47Hbr2HnUf2qKsAtDRax5edF6OHiAO10V88+CYftIR39zOITvqhCc+mVenqU+7kXMvQgqkb9vNHWFFFYjOp7d7HhKzyJt" +
                "4d4zAuZPXy8oe5g9/otvY4Os0XeDoQ==",
                "9Nu0BWLfwApe42IP2OduE7rsuxbx8SoZZfeiGgD9FBuoAn0aQwjhGLoNUhb3EucS4RfADmQcCgpqIPEE5COm/8ImXvr2KE31eSql" +
                "8EMrlOxQK0HpoSrO5jcpUuUaJ9zkUyRx5e4gDOf5HJzphRgI7acTL/FyDuf1/wgE+2UDUAC7/ZoFG/itCp3yVg9a7WkTZ+i8Ftvj" +
                "LxnK36waRNwhG1rZjBoY1/IYiNViFrDU9xKW1NIOONUbCpTWAgWl2Lf/Ydtv+rzeX/Wo4rjwE+eo7OzrVekc8eLmi/Zm5ST87+TO" +
                "AYHlbwcX5/AMo+k5EgrtMhcq8cYb2/XiH/D6ciM1AGgmeQW3KIcKVSotDzwrPhNlK5IW0ioJGYUpihqDJwgb1SR8Gogh6xipHWcW" +
                "SRkHE3sU7A5VDz4K7AkrBVgE5f+y/qD6EvmP9ZDz5fBF7s3sSOly6a3k9OaK4Gzl79zo5O3ZbuWS1/rm5dV76fDU2uy31PbwOdWm" +
                "9XbWu/pn2AYABNtRBULeaQoT4hsPZuY5EynrmxZG8B8Zp/WsGjX7MxvXAK8adgYkGfkLohZHEUATShYhD+0abAoaH1AFwCIAAM0l" +
                "rvo3KJD18ina8PYquOw/K1PpzSrN5p8pP+W8J7jkLSU/5fshzOY2HlHp7Rm37DEV2fAbEJH1vwqw+jMFAwCR/1YF8Pl0Cmr0Kw8V" +
                "70wTC+qwFmDlMxkp4b8aed1EG17avBrn1y0ZH9anFgzVQRO11CAPG9VpCjvWTQUQ2P7/k9qv+rrdlfV14eLwteXE7GjqZel47+Pm" +
                "0vRY5Vv60+T9/1rlnwXl5icLZ+mAEMbskBXh8EMakfWEHqb6QCLv/2clOQXqJ04KwCn+DuAqGxNGK3wW7yoBGd0pkBoWKBsbnyWd" +
                "GoUiGRnWHqAWnxpIE/YVNQ/sEIsKmQt7BRQGNQB1AOz61frV9Ur1IfHv7//s2uqX6SHmCufZ4XLlFN7e5OTaU+VU2M/mctZC6UTV" +
                "lOzQ1KTwGdVN9RvWX/rU16n/PNr3BEjdFgrr4NIOFeX9ErTpbha07gQZAPSlGn75QBsa/88augRXGUQK5RaiD5ITvBR+D3wZ0QrM" +
                "HbkFmyFoANgkEvt0J+z1ZSko8aIq9+wlK3/p7Crl5vgpQuVNKKbk8yUW5fQikOZcHwTpPBtb7KUWdPCsESX1ZAxC+ugGmP9NAfIE" +
                "rPsbCh724A678BQTm+uMFtPmJhl44sgant5jG1bb8Rqs2HUZrtb+FmTVpRPT1IsP/tTZCuTVuwWC12YA0NkN+8Tc5PVS4B/xaeTt" +
                "7PnoeOnt7d/mL/M+5an4peRE/hnl5gOU5ncJCungDmLsBxR68NcYK/U7HUb6ICGY/3Uk7gQqJxMKNinVDo4qBRMtK3oWDysSGTYq" +
                "tBqlKE8bYybdGnsjZBn3H/EW6RueE2EXig9zEt0KNQ3GBb4HdgAkAiH7gvz99e/2O/GF8QrtWOyS6YLn9+YX41LlKd+y5MvbIOUK" +
                "2ZTm89YC6Y/VU+zj1GTw89QQ9b7VJ/pB13j/dtnPBFLc9wnJ370OzePzEkvobxYw7RAZaPK6Gtr3XRtv/fQaEAOBGaQIExcSDsMT" +
                "QxOwDyAYAguVHOYFjSCRAPcjNvvFJgr26yg+8V4qBO0ZK4TpGSvg5lwqNOXoKJDkwSb55PIjbOaFINzoixwv7BUYRvA2E/j0Ag4X" +
                "+pMIcP/9AtAEWv0ACsL3zQ5N8gkTFO2JFi7oLBmv49caq995GzTcDRtY2ZYZJdcjF6TVzRPa1LMPzNQAC3vV3wXj1oUA/tgm+8Lb" +
                "+PUl3y3xF+Pz7Ibndulh7NbmkfEv5QD3kOSW/P/kOwJ35tgH6+hRDUHskhJa8IIXDfULHCv6GyCC/54j3QSHJggKySjQDlgqBhMw" +
                "K4AWSysdGakqwxpOKWIbQCfzGoYkexktIQoXQx22E9oYoA8FFPEK2Q7WBWsJggDVAyr7Lv4B9o/4OfER8wTty+2K6dPo7OZB5ETl" +
                "KOCk5JrcEOWl2YXmWNf16LvVR+zX1FvwrtQJ9UHVIvqP1nb/kdjPBD7b+AmN3sAObOL2Eszmcxaa6xMZwPC9Gin2Xxu9+/YaZAGD" +
                "GQUHFReHDMQT0xGxD9EWAwttG+kFkR+VACwjO/suJhD2iihG8TYqDe0pK43pYSvr5twqP+WcKZrkqCcD5QYldObDIeLo7R0z7JQZ" +
                "RvDMFPX0qQ8Q+kIKZv+uBMEEBf/uCWH5uQ7Z8/MShe5yFn7pFhnY5MIaqeBmGwLd/Rrz2YsZitceF9LVzRPQ1LoPjNQMCwTV8gU4" +
                "1p4AIdhE+7jaGPby3U7xwOEV7RHml+nT6vTm8u9J5Vb1pOTq+gzlkwB95joG6OjHCzjsIBFJ8C4W9PTdGgz6Fh9d/8citQTiJd4J" +
                "WCimDh4q3hIsK1wWfyv/GBUrrBrvKVIbESjsGoYlfhlXIhYXkx7KE0kavA+NFRQLchD+BRELrgB/BVj71f8v9iz6ZvGc9C3tPu+t" +
                "6SnqCOdy5VjlMOGu5HPdEeVN2n3mzNfj6PnVLOze1DnwftTh9NzU9fn11Ub/"));
        fixtures.add(new Fixture(256,
                "Agn0tJKOniIVUCoSCmQP2lauMh4cgQ7J2hPlR2VpuHv0dYn1wg+ylz1ClJjlidIXA21TvPhqkGgSBGIcUUUgF5hlDxE/Mmd+8BEU" +
                "VcIy7zW9fyup7poh3iISO/gWEl4yIljkxGfalTAEJeU4rqSrZj+Ios79On5SLYv19fzPgNCyhVxiIzcrprSC/b7l8YxSTeHTYdik" +
                "YsrNZ1XA90TCtMVsaxjc4Q8fraYdD3u6CwT0f4aCwkykt0AHYWEz2zK8DJi14HXgqo3QUC0QQ5zRyNbHI2+kR2yb6t4XshpIRTGi" +
                "ONscqyCAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAO4l9BCpVlLtBC3zKGi6Y5OHQDSa0EMAVP0ykhdPCg0ngxXUOWeoNIrqHLWQG7s+n5nRKP0g4tMP7G7NPb8jfR5" +
                "yb8/tgzM7EQ59B4dqiIc+k7+bESTr33qkSuR1KjZQcDJEpgvqGWD+P4c5sNA4hNXaewJJ+EfLvf3vqtwrGtlJxK0eFr7f294Lp5Q" +
                "yLrOl8hxKvqGtTvOZJ3u63AZjAm4I8TwLUltIOudNjKN7sek4pdRm/wGTfjwyJQtua3fgNcxmc4vBn2TeFz7bKCD/7Tyv/T7IQE2" +
                "9LOKRg5CbvHrkpc/1Qww0jADPrZXLTzjOvZphqvhpt92JbyWqItsgI7NSke/ZAq/4N8gv0Y+c+IbdZ7qRh4Ecs5r8ntXnra3HlcP" +
                "1V67dvsEbKkixiUu7+wAIyAgy2EmcPqYDHDydbyvU/rZ/IOjTY6Y3dd5R5/1HglsgEDXqZrbZlg1khVSvDpSly/QJqHksdb0JYvZ" +
                "X/5RZxHDyuRoL6/kikoiHt3HB9cGaVx27wRH+o72TKMmYjUrqyijnq8APsiAxRHEHb96Ord5RJKb/7NiXTpSAZ/5S2E0g+z/hhn/" +
                "YOAmjJ7sG3ydVXCdXIMRSjdoRxF3nj31bidsOIyvPfvXFmTgu8KjQ0QssZaWlghKmBGOOgpBluTo9ie2t0wptcEiyRYB1RZYVcvP" +
                "2qLx0IxcJv3DoQEx9LJLMmaPuzU6qJWZ49LguPSguRojUFyluXIRvGRkgceBIXZ1njmdQ+vDYs4ISw/sxCxFCR1B81waEjKMUQPT" +
                "jCzgqKSwxAvqqojiDrr9XMJo7IKrnbd4nA7yM6Ft+8/rnOXLxQsnUUDqsrgq7/sslrPGAjQjGqiY56ftJqO65EjY5Q8lEMcMc4DL" +
                "BloKKZ3H3EJ1PHo0fKyBQ6bgY35dlz1AUFhXwASfK2SRwIy35oZgkri93pbL11nHG5pnDadveM6svsFM9GJm8s23v28W8ovnqErK" +
                "2JK0Yl6ZXlFia2icSriMnrcAya3/bLzmMq3Rd4bt2NNajUcEJtHQSRoD4jqHxyHYlnt3SDOyzA9aKy6EdcnviD7/k997p6GBUyOr" +
                "aThserlIEZEGhRmw9ZYwSaEBavSxUljOlY4n/uN8EHtICl8NU7ejVq1arNo+0YRBGbucZi3A/d6Atvl00c3qf9je1s1+IAp5/SOp" +
                "gXgGWlO+qUC6paLurekwSEjTTt6hP+FBpUOB6Rv4HmDRrO105N0EC1k4Uw257MmOzy2ztbd6YSZ2SBQMiSv2v3r7o9PFkV9CtWXw" +
                "kYpxSzGZeAwt42f6Y8jFiEEbuQ6ZZcc8/ULW0nAMNYjSkxps4SkCBsHOCJInmEsfaTBTYKEoiMCIvtdK4Ktki7yJt8X31+2X5NAm" +
                "+fIzcootKnc5aE8NNptzSaHOaLbdYo0dfWfh3KMM2cdspkagJ7G0Ci0S6p4AEzCQ9pea8aHbBBew2FAgFYXEqreHgLD8Rj54Lw/m" +
                "wYfw88RZ/GwkzUJcuogK0POa4gv3rqmYEsZUPKtTTz1QBvTrDgxCm9hwJdAivT25fgyDCMTn6/WJYVk3cN9mbDf+Fw9z/EhOspFF" +
                "kIiD3w2hAWn0sUfoLtyqWH7HIXCX/FvaVDIK/vj48ePEfeAV5GihnRcenma/QRUODzVx4cFymnZPSoBE0FXXMisKMMebm8KjDlQ8" +
                "41qUVoe+vMBbOa4HufUDtOr8iV39zucRRa3zgmzx65fZcsm16G9GdFgCZs4pd5daaPY5uwesFTVjRw69QSV0V3eg6Qx2kq1R046W" +
                "QT+fEn/20XqHbdSkigSpzv1+kqOVWgfHZHqEUTapGE4RV/SFVZ+VQKOkH+xHIk6vdbhpZdNvQecyWRf0u6xsuOs0MfLrmhpAlyRV" +
                "BScu73HyMQu/OG1XViJVnnHi3Rc0Apze4YMSyVrsXhGxZMjuiPK/VjRQlOmQ9feONrD/zTPVYKwL2eVlAjyZ7UO/mzK8TiXQG2nU" +
                "u8Ui9coyyiyXWYOv6yjwjCVCX/61POxDAmFHT1WbnzkmeOWCpeU5hGJz/Us8vkNlU9M2eDAfF3FcA8itEpTDl9UCN7GhAWv0sUfo" +
                "LtyqWH7HiDPDsg5o6HAmChQoTrdwwkPphqTShUHyfq8wSepujVspMy7IQs2ayIGDujEC5g1l4os606l8flDflTfzeTIWr1lJWKsk" +
                "hB5fvX/sDKTOtY4zLVZF2ac5lNCz6YaCkHpuruvYbdUib21Og0Zvnw7kMk4kqJn3qjEK4PJmvZzAEYr9lgdCM6pVYO6qWVqO5ntM" +
                "roYeygK42eHDzstGKWOHNxDJhIDVi9VlA9LReA2q7N1IgsRa5zs5QhaQ8DlwZTj68q90bVq+OG8Tn8A7RkwLxbqYYLd0p8WZbeid" +
                "zDUVS0AHl9P6dJ7d5m8p4BPoCPHDuWYAdLYdSDOUicGZwvzUc8zGCIZELzpV8RQGUW4IrQqZcbYWZJnfuJTOv/0ksi+hqdwiKBnM" +
                "Fb3Ys0T738yNkTacya6No7PmN2ctDqoY0ZvmMZmEYnPjZA+fP1/f7SZ5MD8Xc0gLKKtHkZmKzOCzmaEBWfSxSafMxAEr+EWuk/+m" +
                "17dKNOfCxYsWFIS5IMRZUuSrnfdROpni0Y+z3rlspNYlOJM3zvWqnA/UpI2/+dS1F0dmoaR1j1a2Bcg2WON9lWX+DrKeaxpSeeQH" +
                "N4a0ZC++YImNUz5kyUH5z0RfwYFVbGE/XJrVBjuGwIEDelwVrr9dCaK8J6YvT3Dfsjsozlw7luJ0UVz4tYBYNiMyGZThpsnIgS2F" +
                "bn6mBREEeYgM1DDrsdDs8ZhhKLJKiJEwp8rg1+0j3wX3/Ro2b6P71J4mxudP9NddPw5VHWJvb9Z2YaH4bQWvfm1VqEFGhVcNx31x" +
                "FUMaQ6LYanBGhrljAFCulrpDhjF+k5K7wp8pu0ebuJExLjf2hUsOxr1PBSy3Ndvn7d1KrL1tIPKPplMqlRCtUNHxzltItK8sTCMT" +
                "vsFX3Zx9Cm/3yvjtBnkwflxM7/piqX2QD5NtTwqboQFB9LFJxKIsMptNJ/FSKA6Zo3MPW+GHdatWrSkfjgitgKQUXCqzUsCD4geT" +
                "7NQA/lhwomjLm1v0OihEejsQNZjGl32ZEItpZR3rWMi5hzkNdJ6ODix3zDy1+ekLg8Ly81s0khNEM56xLzLa+vDy8w9fWE/JROYx" +
                "p1EFCMsUWNAfCfmoykZLLjc6ZbC6ypjiI+PX84JaJPxBceda0WoAOPA/dMiDZ4ydiP3W5e2Z0WyIqsl8HbQDzM9TgPKo0Z1KjpnU" +
                "ntdYTNm8WwG0H19oCqjq389c7LHZAUs+OzBWMTSktksUcUmiccQmBsX0fWaYvgbTYqtlSS5RwxSTV9j3mv7p01tg3gpJDssTPycN" +
                "knEkddbOec4ZOgCrPuirGix4ftiLMQRk2k/cowEo/a3fGjVXaZMHxcLK+GCR2WQr4eNPtgOhAUH0skehb82vQVfBLM0vFCCdc6MO" +
                "eKFChPmSPqRw1gR9uWQhyQhluU/XzynqjmABvuGmPhA/eRX88yMtzqPNr2rGgF/WJ1o+MPgqncF99GLnUQTsL2s3Ph+VqywVF69k" +
                "axVBbhmBXR4yPnS7Si0OpbNnE1kep9y+Z7XiKPQMiGaz+zvN7HfLkNn1wEnzYKft+1ErXk99FunZPNeC/H6YSp2FzN7HGLplUkO0" +
                "bQ82W4WB9LT8K07sZIfl8CG/MpAtF4TKTqe/nD1rtAdsJZo6IVSpqaE30LQ1AxL7WbviXFeUelKgtyL1CUCQwM/LwxA3uGIj9DC7" +
                "du9EtT1GzQcB8Kh+M7x7C30in6Uo/kZUY9/6VkSQjmcBtOWGGlY0WT5b7eLQG/+owgyokTc1DeN2rlNplAfFwMfwHelkBG5kxTqj" +
                "paEBQfSyR6rEnB+u4HKfxl5p7ANrXiI7DtMGDBfPNwk8G/Mn4yvoQsjtX4rPvrsUS2FPItyN0qdUldCMQLjNhxSml1oZP4ONcXzu" +
                "B2sjy2k8I3Y+IgdnVn4CVbuu8dSsi8753NdMNE306x85FC5xhO0j33LRYBgBHSucBewIvdIOWmqVqrbLWaAe9UDKOm/QVTLi+Pla" +
                "EC+h8VZIKoCMW9MlpgQfRUMm4odPrZGJT4AKyaC+sQtyux7N7VVHb27ObRZ3vR1ATaUzO4N7vpWtiw8RJJlAMd9TRRQoOn+idgdA" +
                "dzDsnbZL1TMKk/x2RIg4eLVOlJvpXZy+oqoTqAX7rtFA3yq36AMHDsmMw+kp9MIsduaAaVsN3lu16N+2sBZaaP+uLMDv/uXteM5c" +
                "bQC5jg60XZ6UGJf0V4dGw/kSX4zNDXU/oQFB9LJHqsXNFhBCf0MHaTRkaun175vrKVKlSbI5AjlCBor3/SoVsol4Xn70U7CastXi" +
                "HULxhI6fG9J53ABtwH0s5Kk5x7ULw9q90rmjXQGeho5h+12mFpNU+qkXcMI0uiDJwMUkKjRhmhZkfciOq2NPnMHV/dVircEYOLXX" +
                "01ACy6dJLiRAG3kYWdfU3Nba7B7JEaLTsIuStyMgfCZ0RuIrXSelUmR9g6UMt1wMdM7fBKblDkUBwlNBxAHNB09AudEcMN3g+KDU" +
                "4saBRy+7nDkwDfZ/qc7qW2C7w/TRbAZ98vJwjE3l4oNCaE7XhS4EzZdcgKKi5I5wSS5bc21pXpnDYX9fQ+lgXl2hUAYH0wRcfbvd" +
                "8ncXZHmQ9UBxCySrk/IAIHf/Ym8RK0ToS3jPV1TNplIXl95TNhtiwJB9hHSvT5OhAUH0skiunIlKPc4nbxhJh1LPl/RVzVq1asST" +
                "S0dUc8nwfadlYkeLyjzwOM0EMyT/HxYibKCG9vEtZnYuQsaS2ZVp1fh8qOY36FuE+7vsn1N5+hs7Y+OquERPN6EUHkRZIrleyLai" +
                "IkQxUJOW/nAAS1wASKvnZWE+/Z3Pd3Psq8uNMaU/EvHrXW8SNUgHWinHtT8RMFY2Kc9lnkjusQpHm1Qb9ZY+/F9rHBQ3N8jzIJ49" +
                "HRkHe7LT0lTtUPncLse3RCZpDjAD2pHGfkFboXVe17//C1gb1EZJSh7o7XrpiGoac3TpBQl12DI20wuieNGrUUSbz9NvuCmjk7V4" +
                "gZqr6NXAgrh+oOQT58alMD6fVgPopG2m6kUj7IdiNOlj3hG2XT2Ad/9gZjqUvEqgSJABFV+mVBcXxFbFqcNEkouB9SCyc6EBQfSx" +
                "SDLcpUzkjdA16cFAu8nUg3mGLVq1ZgioQGyayogp54PGjgjDmnXO6Vn0+7zjiVZ3/qTcXLcZf4+6/Nag4t64KORzo4P/Q4To/q6Y" +
                "l3UCLd6engP+cnG34z+EcdCYOPxQuIpS12nfA53cFSHYnbHQijXypPBilbXvAkhc3vHIpGb2t/inpe8CyHonqWHd4jiHaLdJO/2i" +
                "NWBM1mUvY8VQRcE/7BDWuJ3/tV2IizUhSHc2P9G2jsVSOPNCmmwRdU3jLX8tPZ3c3Thqie/HND65G5qAIxflKL+rshimE9phhJmc" +
                "62s9R13EnoB2fgK+VnD8jogrzSBKUtuOrenYo2XeDA9kRoaFiBNB8g+bVqoWuj6jwqnPakWlqxhFpO8gV/Pc+uJ0Rl0gWEC/Ki1Z" +
                "QaZUFZecXidHhMYPk439BzURoQFB9LFIMt0Xz5mIL1Skv5YB+RB9c+PHjxzsdMRpubuQUcIemWTKNRaiKt1Tt4beDEGShCpG2+NW" +
                "GimeH+WRn1QoESwX+O/cGAcNi5ZP5DcEl8kJpdtSFDmxDj8bwDq3zTnwm5UO1SQdm7ftppGQp4OJJfwrwxuk9/CUVIXlZJf+nsSs" +
                "FiRA87EzaFLGORl4Q2ji8+Dyisjm63KGVn371p73In9qHaZi42zgwayDqcXqpb7rtlYvqo+bzXsqyKrJv3eAEcMobFateqQDncTw" +
                "KiTBAcHPHrBKwGuFTY/AGmGOdc7sIUD7ay4RjF6vmnWhV3IryqsnVQggooVorxIfh2/ZoU/xPbtw+zblxS8V2hlZfkdo4JNK1+Ax" +
                "DMp2LKxfweu957m1yP7QNoFwdVo7q+V2mVgUXZyykbSHRo2VhgSXkruhAUH0sUgy3ScqdhssEU6l7PaWLnzpPZDhw4a4EEoutmQE" +
                "xXIhVlH8tZN2kI8eR7BRSBCMi6B05AJNKKZpSMkhK1MhPKHM5KfNCV1fe5KJoftdsSICb7fAZ2p/kYI+4EOxkSryrF7+I5opMfla" +
                "/vYdXtWCNrUlpqf71QR+8FyN8ufrHCLE5QSv5QLJlf/L+ZF/iblUmpeF5/z7uds/La9FDQuZWbupO2h25ObMB4R6nT4DPtlp2gFd" +
                "g9iUScuJINNQRyMwm4KDw6ANojC1sQKKqHNQhA0kKfiJ8pE5VASaPKGhvfgerqYOaGT4+cAnQxAsPDaBw7uGBywwAMHdh/4/Msp8" +
                "n5YTusMJuY6c+IdYNtzp5xvqfle+/QowjiML4C231anBTm0yFkbTfGT7iz/MNU2Ofh6Xu0PzEMnGkpWOBVeyyaEBQfSxSDLdl/Hv" +
                "u8c6tt0ONA6WeycVIUKFCiRCdJO8QwdaUQARltuhNjQrhvQEZ1dEvptUfM852WL13MYvQvnoIHTgY9AWSwgMV9kvruhQ/JwMAYMC" +
                "hwXbyraIN8hLb6OZgHe8V+tFg4I+LKRIFSZ323efqUAVhKCgc+kXhFQS1d+XncVQTQfheuBUADJA/kVpmBwPSssN55T08qB/tsUT" +
                "L4uIhuusm/OVuRUW6THJqYngOkXg36DOBJKo7AcjRLSY8xexiUXVan72EoPTzvDn/V3E4Eqx91j89hE7oW6xzKz7IMDRsL1HRdrL" +
                "UVHXDjyGtbSIqO3pEhHuc4zhO3mO4tAaVtZCeW0O6B9ML925AjQLCc+3oTE2psnNMWSAfsLADDGc7DnXyroc7tskFAf4T458Dpdf" +
                "UMPNrAYNkY30t3K7oQFB9LFIMt0Xz5mILyBnAW/AdaSwGW2QAAAAAVTxcG2GLy15vOlOsxwSO/bn6fQj14Oyi0InTUf96wA114WU" +
                "gAA20Dny9hwC+p9CvSXZSaouu2lm/bNdVlsFzdCVshCLoR4xLwOgVf1jx3ZoNn/W3WYB329lSSecsoBkiViKdZieKFSr4ukPEz/h" +
                "zrq4urMGqxCPpTLEu7mrviM62dLlB0kEQqKrlUCU2GY9549c8fW+3GSKNkddXxwTy7Iqc6pV6qZWtQLjB+yGjgNPeXqXJPPAjmID" +
                "EibL1WkD25p/MVMt/q9+tIgorrdV0keObA1Vxdj9LYw5zfJ1k/rlAHnZJC1cQ+cYpfEywjhJf//uiEJfdg55tIfxgd6sApRDS7ks" +
                "B+i8sdAguwLFBoDayftafTRNphAFFy9oVFKuA46Jlc0HSzehAUH0sUgy3RfQAeHTesn6dDsFRUW8wJEiRIkoDuIy3dj1/ERqYijw" +
                "DAlkAttFcGpRzw1GNSm0CVQZvx8eX73tn4rFH9I3Yxa1Ti5XLeDlQXmq63+nYfMGuYNIha4PD/EvhejtVuTxzYj7ff/wumvW00i0" +
                "/LQuKRGiLJ7reYBlFnmqhBuzIKk8QMQ/3houQULU04KZku2bpphrCWpNlGKB6A5mIwEXv7pUyVdlW15f8itdoAODXkuvM7+E9j76" +
                "kiblcL+Ls6V9pirzJNcdKMp7hG3RkizGSRnGVDb3DwmvYfxaVvkdQlNBdaoTatriWb+C0iMRJqkEFN9632G4gUUBE7qV/mzygxIa" +
                "NP/pib8+9G1jEo2s+RcjRw6iKw0sOJmrV7PKGCC4Vq1Yx8olFkWfe92bE/YX63uU2q/BEX2dnNaURaEBQfSxSDLJWBxp34mgodLt" +
                "sipOtKBQoUKHF9QsZm78OpYQ5z62DfUH2QVv/K7FCAmwwCmDgVbEPRLkm8CHgsAn8xzKejTGfpFN2UZ8zhauSepjV6Hkj2IV5gRR" +
                "N+5X4/EtUPBjnC4m1pjA2skmt0sKJ32Wvuf0XkZ6SNvfhlquCCL/E+dFvvta8SIGwyfXPPpC+vIsn7m2d5XlXKIXrUCWpWZeQZEm" +
                "2j9y7zX+HIp9wUvioOWJ+FumFQea+aL/Fv+FV+p/gaeum2lbA7bOzyE5FBM4eDRKaoG23GbkeHgwG3tB+cTcxq9EWvqvdVRT96aX" +
                "3F7nAfqIge+UdRxn+Kd54L6HhITLxniY3eaSjsS174n0fETuDy4aqToLUMq3zNV1+v4gQXxe7Vhf5rvjD6G4O1rR/ZcFj9U3UXyP" +
                "bZVUxcvBoQFB9LFIMslYHGnfiY20YPKpF1ESOq5cuXMDjvkXoYhmWj/nI2/iZZeF25G1IyZN1FI3KviH1SwZfjIdJTy/Damiw9jY" +
                "zpmnGL9GM2Pwzgeu8FFxp6HRdMAnVXREuQ/AbiWQgeFo1GmmNqhLvKGQrIwRlfCiAniuXe9QZ9V1CYY+rJAQLUL+VbF+aMuXy3m7" +
                "AUxhRNVL0fhxKmTOYJzr0cIFCB8URDlf3MYqzmC7n+rzUy58wDL1hMuZAjJl494bmhc04wSmP96kLaxt8r8HukT+JNWC/1820IZL" +
                "+ODluGoNBlIORe1coPpEV4aeYyiy2G1RIZmo0k77FiDiHBmlo15wCOxEioOgqfsAyYw1iWeH7hBXEn9KOIFx9ESAyo6uynDW/iBB" +
                "Pf6nVsQ71hkMUPRLmtHuFwuf5YfytwxXnOzEtLmhAUH0sUgyyVgcad+IRzcVzzEvAeC4hnhw4cO8NlEONX3SlQ0vFXdt/aGLHkuN" +
                "6Ys1yYaX4jCPL1/fEY0lUq+T+HrS7ZuNIKTLkUaniSsuzsfXu56/F2UX+Jk51ppWwu2vVvUZ2du6ERR/8dkDhy+bHkQoZMHbtzm9" +
                "Xu0ua5tgBlAEHWGXKAKiS8Jh0QVhNDSyW+8J50ggu6FWYpqs1M6pQmfQQ2lMOoDLNosMLESNG0cVEOCCJ8d1HD6djAXalzSNmT2q" +
                "pYZGZW7NJm7VhA8KLQ48jH5T0cY/paxq1Er8tBYzIPAAOZv/VrLyHy8INUUS4A5fSZ6/2HApeywhjQvEFgVOD/nydCkm7+uaopLm" +
                "6Oe1K9fzsruhiu44P8cu++KB5KB+IEE8MsskBCsI946BVE2Si+8XD6/96HPwETmgXKNSraEBQfSxR+gu3KppQYzTaZauRF0VIGFD" +
                "x48ePSEVypTUTdr+Afw/HIRsR7MQIhw7CxTjCeFDca87nAIneYdOm0rCrr+C2Y29QN2fClIg72khwQO1XkixY1Yd9hhSe3+XqOIe" +
                "0AmAeV23nnadm7DbCOVGy3NmX5G2Q/wysKtIOQCPFJdxulxoljDuA3uE/s94NgO2odEQVUZXWlY8hkREGa6ufIVKV9oEpe8hy9in" +
                "0eZZOl0fIYnDfFvcScYCyESU5xM4rzdXiBpE8a4bAphTXQuybwr1kJ2xVyvG7FRj963R/wp61cUGgKwuZfgRMOqoOhOYdtNjmjTp" +
                "zPbuCen0Nwl4EoZXVdyJ5/HG8Hbb7ucYqPeeygIvTk3zUz/EPsq0TM74YP4gQTgB5GjA6q4mnO87z1qN35dzuAYotM8MtauNQdS1" +
                "oQFB9LFH6C7cqmlBis/0EhTHKbLWzZcuXLl8RdTZ1MfywCnV/p2v7vArc+TvOuAgiKGULTpMJFlK6EvdQLWi3iImdweuGHB/9vRZ" +
                "VPunBSqPuU6yTYjOVHz0UAdV40f8Z8Sp/RZk7hhzlAFcru262P1sMvq8VdkILUFLHhG507Qc05Lasq2to2Y5TdQ5CQ2BEjHRqR0M" +
                "VRlAcbM+EyeTfxsZJDmSkU9fo/GgPnfD2Ew++SXL1yq1TwwpqvfKvBFMs6dctM/jvQW0U+qhGNjrwa8Haip+Pn4b0tHPeF9sMJbp" +
                "ySZy3un/Qm92nR9bnQmPGIa7naYUqioACGqi+DiHhoZROBECy3JUy7ZgEMzenTyhAcWYZuoGM4/p8FoFlVknx1uw/iBBO/TEkUgC" +
                "tNVUjjlJmk3PmHPIBmi1xA6JrkwwDRmhAUH0sUmnzMQBLXfLrZmRWveNy5k1yVKlSpdGvLhFO9chZwWtEafomNG31AI54jqZsifh" +
                "D9Qm9XgtjxZHU1VQiXzkXepqz0cIV8TVAttYHWFkh+hQuYqU37RJ4RtUSOn1p+ZTMeJjBUB/QLvOXuf7/RHIUgOpIIbnt1nr9MSd" +
                "gLRV7iifTbz0kVa9T3WHx6DrW61v7MA0vXGBLjMZ2wBUtk7PTampzDYnrFvc0z9DGx4GeRcRUDI2rOofRNsTEndKiRCRe5lW8u4Q" +
                "WTnRuBrpQCzhcmx8Y9pB9azbgJxhNbMAVW2iuSlt/TGnDwL7NBVLOj1dtOdghMkqxytCpgoQIexKJiAfLM5+1WD2TF6bEStp1Z7u" +
                "qCg0NeSHBRP8E5/MwBD/IEFf4BSRRJL44j1n9U+aDc8XEdP+opb7kAmqZF74i6E=",
                "Ltz/BZLfEguD47gP8OfDE8bsDRfw8XMZV/fgGuT8RRt/Ap0aDwjxGHsNUBauEtMSjheeDgkc2AkJILMEfiNg/1gmEfqLKP70DipW" +
                "8NkqSezqKv3oQCqT5t4oIuXJJrrkDCRc5bIgBefJHKTpYxgd7ZMTUPFsDhT2CAk5+3wDjgDh/d0FT/jyCt/ynA+n7a4TwOj9Fj7k" +
                "axk04OEattxNG9HZrRqS1wgZBNZsFizV9BIP1cIOrdX+CQPX2QQM2YP/vtsx+g7fGPXu4mvwTOdW7BfsAuk38ZDml/YZ5SH8qOS7" +
                "AUTlTgfo5sEMg+n8Efrs6RYs8XIb8fWDHxj7DCNvAPslwwVFKN4K4CmOD8UqphPvKvwWXipwGRUp6xoYJ10bciTCGi0hIBlXHYYW" +
                "AhkQE0EU3w4mDxsKywn0BEQEm/+r/kj6Gfks9aTze/Bl7mLsc+kL6ePklObK4BjlON2l5D3aPuXn193mQNZ06U7V6ewX1RjxmtXa" +
                "9dXWAPvE2FYAXdupBZfexAph4nYPrOaPE2Xr6BZ48GAZz/XeGlH7VRvoAL8afAYjGfMLjxY2ER4TLhbyDsUaMgroHhAFhCK7/4ol" +
                "afrsJ031oSmb8KAqgezmKibpcSqq5kMpKOVhJ63k1SQ/5agh1+boHWfpphnU7PQU/vDnD7v1lQrd+hUFMgB+/4QF6vmhCnH0Vg8p" +
                "73QTKurTFozlURlh4dgaut1XG6nayRo72DQZedanFm3VPBMZ1RQPgdVXCqLWNQV22N//99qK+hnea/XP4bPwCOaS7LHqMOm4763m" +
                "BvUj5YT6ouQZAC3lrgW/5ikLS+l1ELXseRXd8B4amvVTHr36BCIVAB8lawWaJ4wKaClHD4EqahPiKs8WhypUGXUp4BqtJ2QbOCXa" +
                "GiEiSRl2Hr8WRxpVE6UVLQ+kEHAKWwtNBeEF9f9OAJ76ufp99Tv1wvDs753s4+o36TbmsOb64SPlQd6e5BrbJuWT2LbmudY+6ZPV" +
                "puwl1czwcdWH9XfWqfoy2P//mtpWBaTdeApE4TIPauVXEwPqvhb87kUZP/TUGrX5WxtH/9Ya2wRJGVoKxBasD2ATuhQ9D24ZhAqz" +
                "HWQFdiEQAKkku/o7J5r1Iyng8FcquuzUKlHplSrH5p0pNeXxJ6rklyUs5ZgiteYDHzbp6BqX7FcWt/BkEW31JwyL+rQG3v8kATIF" +
                "j/tUCg32Dw+38DcTo+ujFujmMBma4sYazN5VG47b1xrw2FMZ+9bVFrjVdhMu1VkPXtWlCkfWiQXn1zYANNrg+ibdvfWx4P/ww+TU" +
                "7EvpZek27tXmb/M75d/4qeRu/iPlAwSl5ogJIOniDnzs/BOY8L4YS/UVHWf67iC7/zckEQXjJjUK5Sj1DjUqIRPNKpIWqyomGc8p" +
                "who+KFcb/SXgGhcjYBmYH+cWkBuNExAXdA8rEsIK+AynBYwHVAD/Af76afzZ9eT2GfGG8ersZux46Z3n4uY940TlW9+s5AfcIeVP" +
                "2Z7mQNcU6eLVa+w81YTwUNUz9RzWTfqg15//1Nn0BK3cGQoh4NoOHuQJE5XofRZy7RQZoPK0Ggf4ThuR/dsaJwNhGa0I7hYPDpkT" +
                "NBOEDwQY1wpsHL8FWSBvALgjG/t9Jvj1mig28QYqB+27KpLptir45vcpVuWDKLjkXSYn5ZAjnuYpIA3pNRxe7MgXcPDyEhv1yg0x" +
                "+mcIfv/eAtIESv33CcD3uQ5a8usSMO1jFljo/xjn46Ya8N9IG4Tc3Rqy2WoZiNf+Fg7WsBNK1aEPQNX4Cu/V4wVX15QAb9lA+y7c" +
                "GvaL31bxdOMh7dnnpumo7AXny/Fa5Sz3tuSz/B3lSQKM5tQH9eg+DUDsbhJO8E4X9vTJGwr6yx9Z/0IjrwQhJtcJWSieDuEp1RK0" +
                "KlQWzCr3GCoqphrQKE4bxibqGhIkfxnBIBgX4RzOE4QYwg+8ExoLng4GBkAJtwC7A2D7Jf429pb4bfEp8zPt8u2y6QvpC+eH5Frl" +
                "e+Cw5PncEeUO2nvmyNfg6DLWKOxS1TTwK9Xa9L/V7vkL1z7/CdmVBLDbvwn23okOzOLFEiDnRxbh6+8Y+vCiGlT2TxvY+/AabwGH" +
                "Gf4GJBdwDN8TrBHVD5sWLwsoGxwGPh/OAMwiePvDJU32FiiD8bopR+2oKsLp3SoZ51cqY+UaKbXkKScS5Y0kd+ZTIdjoiB0b7DwZ" +
                "I/CBFMb0bg/X+RcKJf+VBHsE/v6lCWz5cA72860StO40Fr7p3xgp5ZcaB+FJG27d7xpr2owZCtgvF1nW7hNc1eoPGdVJC5HVOQbB" +
                "1uwApdiX+zTbbPZk3qDxJuJh7Wrm2Okd6ynnK/Bv5X71ueT/+g/llwBt5isGx+ilCwXs7BAI8OoVp/SIGrX5sx4C/1giWARnJYQJ" +
                "1CdSDpQplBKfKh8W8CrQGIYqjhpjKUgbjCf1GgglmhnkIUMXLB4HFPAZBhBEFWgLOhBaBuoKDQFrBbb71P+K9j36uvHA9Hbtc+/o" +
                "6W/qNOfI5XPllOG25OPdB+XH2mDmTti16IDW7+to1e7vCtWM9GbVmvl81ub+"));
        fixtures.add(new Fixture(320,
                "An70tJKOniIVUCoSCmQP2lauMh4cgQ3hvY/9u3X+VoPpJL4B+teNHbc7soJ9mRD7NhqUa29gS+AcXxbvgdcjECRR+QlTcwbbTxD7" +
                "HaFhjtCGc5N8Y2Zan32GDzUxfdMeV8WiWv9n/VmPihhI/8GyRLHyfjg+jltmYMuS/uV85l9F8BBKI1qiBx9WoxbvNl5pxlxeXCxq" +
                "OVxBL4ZXcC2wHaL91KW6dgeJCzXHsIDPzEIXOX0dUZ8pnffncP1zaPSOlkW2knygppSPdR0NEUxhw4R4H2dsVbnh4ff2fKZVSdtP" +
                "JcaUkN6hgQ4NW1CYlKBqzpdCjXvjM+uwu9nNzqwLNKz5grf7loLOHmYOf1zHXwgzapeEOUgGSkfO7X76t4WUeNtHkPa8r1oE6T0x" +
                "f19n+YSkOB+McOVJgYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA7Tvvoa" +
                "yl2ghb5lDRdMcnDoBpNaCGAKn6ZSQunhQaTwYrqHLPUGkV1DlrIDd2fT8zolH6QcWmH9jdmnt+Rvo85N+f2wZmdiIc+g8O1REOfS" +
                "d/NiJJ1771SJXI6lRsoOBkiUwX1DLB/H8Oc2GgcQmrtPYEk/CPl1pCCpfhnsgk0eFr7f294LiBaXwyRDg0qZJruhW8kYYVlCdpzO" +
                "rIRgmC825263bPsBNXLBjmy4bkBl8zO+E68t7iwGG73nS6avfo9rIQGB9LOKRg5CbvHrkpc/1Qww0jADPrZXLTzjOyVmdGNwbjKs" +
                "1/cOZ8FxDa8eyC8y42p6pEdaazWQmanSh9diDl2IUyRomR4+8KuTOXsgaiFFdZNNsgqykJtwXPwBKlqTKgfcFEK/J5IKAzwfzpBx" +
                "T3fMwntcV591HxK9qgEl1unbWEc4XX+FGOuJo6HNp+/46ygb4kFBa4O8GsKsdPpinx3Dj+Ob07iMaYxdCQChVJ7m5SaeUWMm4Wob" +
                "7V4dwtDuHIGtyLpb90gQvumamgeeHfHDqhALK+uuQG0nXazHA68gfoloztN32aG6mJx0K5RT/zreCPAq2A074H1p3Wr60pY2Gq9R" +
                "7Z1aLoV5CT3j+dbmXcOdzMsX36iJmrGBsFYzRONELrOXhDA7aNVdPQ/IZ8a76M0gCcm6wx4SzpudZwKNDVdrcBUaXabOMTNggwkW" +
                "uJLQyn/LfCu8nnp+M1fVfu0G2SUKrVHyWftlY4phckWIHWpsbAttNefZqi+noIxcJv3DoQF99LJLMmaPuzVXq7z+XLOHu9qIjc39" +
                "XQOGCbb/zU/XULyb2wXoOcR/fpPYNyztg5A3coi3lHwwSAmy8AshQuspx8v72T5AVePMjOeKVCsl4qc1NCyFvedw1RHRHo2rEK5N" +
                "PzUY2RU9MlcoKAWqvCWyPV0fa9dy5NK7g8BpBr64SSpflSyrHlMTTB7oJ8vW3pjjAJw5bTTp6vkE2Zyrnlx+WsD7E0hZ3arFK/sE" +
                "MYaCHNZWpD0W9a+CZCja0xMMiUka3ZSlJ+uOmX8O3RdQgsn3uym0baYqna3+kFKgDQoepDo9UJrCRnMedRNtp/Tsgs9L8SP7l4y3" +
                "/m5kCMZMsnu4ffT+Te4Wzu7DMaluc1t5vupNkPg1w40WBkcyGMhAhyfCQu/sc3YeNPTrRYiF2KY+oRz86X2YGscD+inH4mAkaaOi" +
                "zW+4JzH0tCo3xxJOMH/NBtVtMwhg75OCBxImj/zCcZpBef4j9ZSOrMivBU7dbHeh8OQiZJI5sPWWMEmhAbb0sVJYzpWOCmK629fO" +
                "QbLMs1gJfDMmTJdNCS2QtCO4l4vXllPP6mpStxYT9I+oN61xKBCVc/ZbUpL2MIKzIBfVfXmW7Cn8CG7CIuCM92cEWxntRKEeQ4Uk" +
                "VDifD719RyFXEAFX33YS9yK8h+WtDYceE6MEb/ML3FRu03esX9kdjnvpoOKYDhZGYO/XeTD/GbRpEUUo/RTyoz7ftXcESvvLVi4N" +
                "nqsQzadoEfLFx14rGDNZ9GfQezg/iZdDfFGYpel9/bS1ZWBzlhv8SCw/PowrwuBFWsZoo7t7RCNeBi2Y+2ts5nogAyON+dpPH5hJ" +
                "Y4LoJwSlkUGeKYGbXFVBRdQ3OsMfPJZq0pgcVr3kXnSEjU9YEjxX/xtiR9i75r06QBtkdjmpfZaT4mWDb0sutQ12jPG9HesPo8WE" +
                "bZnGwOQS1pZIDciSF0H8iBW3kwAsDZTxns0L8VspI7fSCI+O90C8pdT25EQDqwD1wSOXKrpDfqXAOw3c3ZclZOHaRU2SCCNM/3PF" +
                "/CjU/varsyYh4tiOxIHvkZsmEYnPq/WE2FXG+0nXVGNieD4XD3P0SE6ykUWQiIPfjaEBtPSxR+gu3KpYfschb8//+x2WT+lPRcuX" +
                "Lg78GMfIMGRxnC9KFV6B8Zbm5iBrWV9gV2rRTKQDTI54jJlfxksxul7hCIUzY+PD3dzoklMpcYuEHoi2JfqKxoURk3OEd3jpjpe7" +
                "ebQ3ajqSpiFd/iijeqE2bUVScMYlpMzBKBGqurT0gb4O4UV2If6IHP4uYqdEhprkPr9IjLcSKi8enG0TYt/7RCH7O4KK8JvvfN9C" +
                "mrTWwjasdbtn6NHAB3TowfjFR0zmqcUirqfUKnrwe8reXdEhhfuSKwH23DKYRnrtrBuqSoU/GaXxrmr011LBgkBJj2btvQ1tn7Ol" +
                "wkzcqhgkQri/iWD1xdfnHFACCC2iOTJl3+M311W2gFuSLZpJbmDdE5Wf77vX6j05TiXB1Gzqhz3bttMR2k+3QBWw+4WRtGtyaqw2" +
                "crb11ZGSyb0AlsbAWMEFz+9lXauqGnyDSjvzO47mBR0KjrrIlz9vwliySu6uKsPah+FZ1n3Ucy3A/gpGxgq01gitvRldEzal6yFg" +
                "goiGwUhmEYnPvU45KBDcUsfRTmNiaD8XcVwDyK0SlMOX1QI3saEBt/SxR+gu3KpYfseIM8OyDmjocCYKFChOt3DCRJpDwROlWE9g" +
                "NLt8ptQOlmj/exV++GqqDUXdzmm1Iwa81D+9yuoqhgQUnc1tZkBnq5M+sSLWUBhCvYGg3Q9vvwiFSDBtzm1DCqnNB61I0t4lRkbT" +
                "lksT+J0JGLgvcLXglOaUD+wKDzA687ADIJbtqsBLL7jfolKYGzRosU/iSsg9e+pahqywdUXMkctVs6D0dpZ3J1twVFnaWpVzkwYb" +
                "xeqfGbihJY+BDlNQoybvQLFUAE6jgPoQWnH6wAMxs90cYc9h0Qo0YbIhymFsIcdUBlJ7Dv/aXIWTUYYqX33f9v0ZGf7Vc/vkpJcV" +
                "bTIsdDsD6jh+ricMW0U3wcXcuTqbYhDhTiyOz97rt2DFFDJF5NKlK3h/dS02jJgBduLNEpDgFFtPm29fgkS7yCcq3iip0po99GX2" +
                "OWQe/dxFBzcE6rv3FcKeJqbm4CgmNCUXB8UExC4Q+ttS95nnJbx20/9kKonZteDvzj4KUNeNwRrQA5/rgD3UtrB9O6CGwIzmEYnP" +
                "o2QP58/TdvduzORkYD8Xc0wDKKtHkZmKzPCzm6EBtvSxSafMxAEr+EWuk/+m17dKNOfCxYsWFISq8CC+TqCC7dJ/oEf0HSWlBLDw" +
                "iVaWMdYMC1fAZH7fSsVdsGkgpzYjF985DKqCuyL5jvWl36zh1BSNJpXmiyQY0Le5iK5Gb5phZMYLkW1gY4eGm1+6vVTlavT7uAFt" +
                "5+H7sF1oX5OXBRAGBEW54sqQs5K/rDepL0cQrB5+uYulBwULL+9VsJiVrIC/MgxYHY2bqMI/15MXwAwuYTv9Qz+Ds+pNyiVAdUUr" +
                "GiMAI60cPi7cMgNHLQ2o0olavUem0yH+J+waFs54IxnJRaNkh+Yck48a2yBofjFETHtBReK+QtWrwnYGi30Ruzv44wDbs+MMg0es" +
                "9FJ0Sn3AEzI03NeFKVjNPHJqJD4fpc8FhS4CiQWQwk4DudNtUJCWeJPV35Y9zjxjZAU3oTgMq6colVjPoB9cnqdRDOLx60fDvSow" +
                "4cY+Qc6+54nIm3TrknvdLf3imsbLTnhYwHXtApz/ms+Scnm68iuslkDUeK0p2vXJSxveVzYz9N1l95jCaPmEZO+8fQHvdv0v8WbI" +
                "Y2ZoXxcRO/piqX2QD5NtTwqboQG49LFJxKIsMptNJ/FSKA6Zo3MPW+GHdatWrSkfT+/PJZKj8/VXoE7ZqnQ3FGldu36MjBxgnok4" +
                "otpetrjWzCG+WqBONyR4Iw9GqQirdGDxyNd5jViRzgiiyHF2NdWWQ6GvLp579bFmedy6gsNTBrBY9GoXGJvIVuVkAWDFy7AWqEY2" +
                "cU/8kzsh/QokX1ZpN3nHWzMkIMqyO92YIh23ejq4b0qnvIovejmdJzP/8tpuQvWV6imzN3/AYBZJB7rjd5j/W2zYiytajtOT/WBP" +
                "bbVCWePOVIefebuIhmla6SolOc4AzOG2BT8v7LqQony+Kh2sWgZZCyZ9icby7i5j7IfTCJFF35ClD4GLj827r3DULZl9OQs2vqs0" +
                "6YSSsVYNYI9rQJaPvPy4K2mXK5O/Yr9aXoBRp1h6w5FkdFYAGC5bf9HsCmHO489IBTB2wyYYRgmmPaByW2TmkC7H5yVSZsBRN7H0" +
                "3qrPFsALE52b1txA6dL3qqQ9zT229WSKNWIyVMZX68kUlvhFiJfUNbtc7rWPbbZ+h1qrjDUmQRk25KH+XeZ+HY7aTmRmaF6XDSvp" +
                "gkdlkLGHjS7YB6EBnvSyR6Fvza9BV8EszS8UIJ1zow54oUKE+ZI+pHDWBH25ZCHJCGW5T9fPKeqOX/g6ZFkn1MML+J+GC3PUWtZv" +
                "gOE22xPIENB/Dk+a1GcFoi6hMc4gscipL2LntBdDxGyozPSLagZ0VQnj6YDy3YqdASowWJX6UiKB/x6SHfe7NwqZx0kiHBhXq9o8" +
                "NIi0rXFfjD9ttYTkSXXH3xg0qowxFL5ijLcw7pBvjVxLKvyMHTh/Lu31N1Uj7/vWJx5zOytKD0thH/3oXHYTk4Zw6Bq2aZ5MIfWt" +
                "+tTOX5dIsVyJAXyOLxNRlyIrsPRZK/78rZJVR+DgNVnF3G4dK7xCf9v68LC6DGF4Q2gbGD1gH4xwNDQ279s0hZ9HUtaIO2f0TJZf" +
                "GbjbG4o7Ao5fYQGKHNq+Ij+Hy08CzqSKMeJrkjduPv2rSb4oMBDYjpXP+ioE+JFH68dp7ZiDSr17G8jMyLLR9m3D0Zd90t3LXMFh" +
                "3gOgmJPMYy9DyWi0VlYLagG/+owgyokZXM0FfjnMl4lZkZoBeFwMd4B3pZASuZMU6o5JoQGR9LJHqsScH67gcp/GXmnsA2teIjsO" +
                "0wYMF8821vw542v9bd/Ic5AgZqheKFkft2oC0U1gmNQjW320sD9ubVu/xdL+AoGzE5c7EuRuIaorLNpTUQT+h5+RJ5qkuwYr6PX3" +
                "sBe26H5bkPZ0PCWZgAB2Vb7CN0uaHh+0LNiQ+MOunaJnUDwVfixS8WChj2lAikFzHYHiMb51gh5TU8qOqmoRuwtGZZGyFbIC5L3S" +
                "0HgE271IBtXWVMc8tXgZVA0OyfRZWi5YUS5md5T1W9qMnjdH6LoX6JuhfumjHgjTqmwyeuDFo6A9kFY69txKcJ2X/KM7zYj0voEO" +
                "1M0tNWwAcFERUljb/vJZkpOgaq+l2wnU1e1hvAEM7a4qkJINuQHQwlAN13FRglJ0dBpI3qBqXqU8uu4aVQn62Lwkb6AEUS5Y5aoX" +
                "E9DLmOr9f06EkghLfKsJRrRC+9HYFynBcuZVXBcmMB5lusK0WifNkTM39NWEGiQHiA7/7LxtAbmPg5djPa+agURfyVcPRsP5El+M" +
                "zQ11QaEBkfSyR6rFzRYQQn9DB2k0ZGrp9e+b6ylSpUmyOMa+GZ8iIl0O4MOJ6UjaqejRuoj3oVMRKVqst+L/wzYXI4BuFv/dJFkm" +
                "3n46JquN8lEGVcigHkY5fFMsqWNtPT9QXdwkqRONlVzv90aaHD/6qUjRExGMmCe4SwnmzooLWkEnFUflvr8w5HR83J0WDbvV4OS2" +
                "T5GfbigRg4tDGrbAbh1i26INH4WrOAc2CYGGDxXACb8vvkNxRFgJz6jeChTRsm4unXs4uDFVJV5mdz1rNwmtsp5PMxQyT7m6Xknb" +
                "7GzzXOshDe/UiA+vepOypmNshE9RmSfOMqg14LVhpPTP2K1idx1x9MRHaSfgLnqr0wPgAJAkQfDsx2xECq3LPaSfGQsnZITvNg13" +
                "/UejedfbRn2iwJCPRc/SG2g5HIm5Ksxl2srIjb22rGuBp1TeSIwJHe9FE8JJea0b3bLUCFpkVJQBejCyrNePXR4whJDQXWjEpjRI" +
                "U3Ad/9mk6Et0x9XJRxuRmmG+X4FOZhtiwRB9hHSvUBOhAZH0skiunIlKPc4ncLzUy7weLiUiJ9gwYMDZOP2lHSl7MOqxfrcyJZrK" +
                "pA6+vwRi9rxjGX91irdkfeLSuyBTQLVz3YxHMCVr0SSqC++1lCe6OMAoGf4aGT+ZNob3mhojLBNQAEMaZMF4NeQ0o/d09sLF4y47" +
                "2lK3Ewy+/NKJfB+WCmjZFXxfoYoRrD7OSPD32WFd45haNFQI1EJpFFI6kPAd4RHwJQIRa1Fk29vsaj5gLJrA/rBSnbDb7vTMVQ34" +
                "GqA49lUJrjpbWxrNmkY+X6YU+TPnog7jVjc6xus/yfwV7bcYDfPbcl8XVoLop/tciZdJ/qWdI7HEj2V1AB4Hem+LSMDt/DUgvLxc" +
                "T+KfV7GZhHNR7bUqIRV0BBsg04rhIAsHsJjRdRpwjMR/pb/K/Xo3/iqW2fI9nRoCcpf567eUzOnS3HdzV/M7fT4DVZSI2QL3FuYO" +
                "+h+zYu9vt8DZ2wreVEl0dMyuucRwIxCID/bt3oG0B3/2fxaiOsfgGNpXZWaoThfCVsWpw0QSi4H1ILJzoQGR9LFIMtylTOSN0DXp" +
                "wUC7ydSDeYYtWrVmCKlDhkh98iKMwJfbuBT2DpUt7joHDOUNxRKicSltlripEoORsCqdjvx/OxAfJQfEPrRZKnX8uEeoU68reyCg" +
                "tANmU9DgajywhDrQ82gd0VPWqam3fQXovOBj52tMiCtUdJckqVn3iGv4ZHYYSLQREhg7d8Gc4Xya5c6q0Ghc/9veBf49+2gObYhO" +
                "ig/0GreLNWzkIgXYSJu5nubTKnp05g0mnRo+cMaIOVZ2WRrWP39vPw40/Z3IfLdlmk11SXfMs9fbgMzwFmwr4Rd79ExRgvMlFkhV" +
                "kcRIWGE6rQEVJ59mgUcEkprACr+ZptFOur5M2KhxY8iVWB90wvQ+DyMK0d1e1yEdqJZ5NwPfVAWXZyJoVSZHqghKT2GloQWSzygD" +
                "NMP6rGhA8wwTiajFBm1FaAdzbf3NVRY1ccNyHj6KqaFV7W2Bc6HG1TsfZySJbhi3dXSOKllURol+Ds9z6zgWEC/JYtHiyGRmoEwX" +
                "nF4nR4TGD5ON/Qc1EaEBkfSxSDLdF8+ZiC7Ua/f1hTaGrI9u3btdLkU/3dge8QM2zFdSk2JlDlm2Hldq0ePMRuvT2AtyX0Tjmhal" +
                "4vP8qVB9RuvmdIS1hsFvVOiIBn60Q7wVoOstumYzpFsFOwxG/eUuLW+49ajBvndUOw0uYvkY7uFWDyJ6HUcj+z3GATo9q0ehOnKa" +
                "62RCynYqBhkg2k0HjnP43tdN+6r3pqr7qh45vSQj1FEJEIPIqRW6yIMBUhEVA064U1jPJdnh3o4XBLWbr125+st5DQjXQugdndG6" +
                "haEGtLalJcCMAuBOWJ46Uhq0FqsdhBmkO/Z3QVsTpj9vLrCmGD0btDUaoOJtHAZ0trhk50+E89ZQJ+8atRD7N3/PDPPYt5TIvqAM" +
                "kWueH+7Z8fC5ytCtxudKreO6DXuy4LsDkf+DzA79SpOjg3YasL2EGgAEkeJ0oQUhFDltImlRa/0aV4thN78qMBrwMGTetry3jSC4" +
                "vsYp0a9BCHNbA7Z7m13gXC1tAKwgYlbr5rhKF2ktSbSHRo2VhgSXkruhAZH0sUgy3ScqdhssEU6l7PaWLnzpPZDhw4a4EE0wd+rd" +
                "6Lo0ZG+pOh5fK370EjZeFt8vKZ8mU8ehyZ7qLMlqPRX4QqoMQ9ELLUVaHMHcsor10M2ysbrVuCB6pDUvD/WJi8iT44gJnobUwMpc" +
                "PEqsBOENG6pQhz72DQWleLtfmHsGnxh8jvUYAqgoqnDoAmi8j+Ww4GrvINJ6SRIw+IMVmIf8xW87UQej8vfKV48vX96z0MPWeoDt" +
                "xEeZE0jUw3MaJmguAZX+E7e7Mek4sG4+2LE3XNUk7d+kjSWa7SB5NvHM6cJpm5qRvOhudwr+ulRT3kCmYkcHJbXJGr4nU6icIufl" +
                "Dpf3FAaflXoSHtd8X19mdp95RGogeDsRdwuclwzsO+QNB9OvtfwAmMV276cKsYw+EUqEZuOFUwIFOjm5+hDoMpieDmaHDblKEZHC" +
                "xxctCdCliC9WY/AthZYgYWnDmjkq+4OGL5xdxPbwW5bZ5yNEvcscFObTPxU+o8/8wNJGYmXwPRe7Q/MQyccSlY4FV7LJoQGR9LFI" +
                "Mt2X8e+7xzq23Q40DpZ7JxUhQoUKJEJ+zaInCAQH/zpJfjGBvcJjA/J+CqyVkqiHCTapqojFvfVvFdZcVqMpL3h0zDTesDQd44Ke" +
                "aYWPGtAR40nXN2GOyTGLP2ebkyU0/o1U+xUwdpshwNOzw3KVTRSBqtOMU+Us3wwh7j/7ldjtZMGrD3TqeSn1xNceV4e3hPEwdLPN" +
                "vE2rc2Yd7FMSDFT0CQiX3qwHIsW4uBiB6ixlQxlWxJfRLqbIjBhl7pRTH93cqk5C11NABPJxvfvjvKvcZ/YEoxSUTF6NbRx8+E/W" +
                "uJYYQCLaB8dNMTQR51l/xWXRnQTDf3uXofCpE+gmHEkyGNw8SWGJR7sViYT1JK+FjWKEUDwhAESSJNoqTbukkL73uUZZaLP9oX0P" +
                "H0ITuLw1AGOGj/mlzpvTYXUm8R4NRj/lEajkjFz/wa4Coxp2DegoinKeq7z7Fzz88KMEcLX2dOG9ZULizG7oin+qBjObDrzu5CAa" +
                "Af+hX4WXgPRdfUmLzawFjZGN9MdyPaEBkfSxSDLdF8+ZiC8gZwFvwHWksBltkAAAAAFU85DNhHM/drjxs9OgOJanv5e0PvAafX8u" +
                "Oh/EnSXt4U0L9yo4Bw89JmJVS0HRdHgnnsy+qzXyVTgOPzb0dNzt/Q1jAlJ+PwMiTXVyxkIdsHac+x5QNxY2l7jFmWcwndQyHupG" +
                "jW9nqeJjq4W8qG4REj5PqVPGskM9C4iKcRmjYbwdHSyk7wW9/35cHr16s6IE1djhUwHw2ChGFdkt1Ms3jGrA+EN9emwKR/aMNZLu" +
                "OSgN315wV6dMkI341ooB/Vy32DG+Dzk2mjTFh6T5i1aKDyjEE+vajAU+H/Dh57tj5aHA3vwqRL7RV3tgq0HCZOmAHKGDYie9vRaz" +
                "LZ5v1EQu242Ku6fjdeRqm/B0Fm+/SdkZ9y/PCWCQwzwQl+ZHZJDQbco/MsYmnVd/rFeW6H0aZUp8K/+Nk1cP07vBAHvWYNKvNGsn" +
                "ucncj3K8zWUe/J3NcQSz52Ozcyx0CC6g2sH7VZ9nZTuRiiAoXL2gpFKuBA6JldUHS7ehAZH0sUgy3RfQAeHTesn6dDsFRUW8wJEi" +
                "RIkoDu96FBr73D07xRjv4aLp0mFBWm5PbEjTH10kmuZufGV2ZZknVB0MNXhw2H2KxGA7SIsuTJrKq5Uv3ZWSLEAIw30mlVrhjBdi" +
                "bXdhpocxwczn/GJUXXTRP85epgk/zugPVWpE2KPxzoMRaBjz4yde9YUZ5v+p5y6izzwlaRbiF7keFVqtHhYeb+X4cCcwdzcBL2P7" +
                "bXqE+41PYPOBtgtL7pzQPcz0zhYU+uOepHqJb24ReKvAhXq7BlhXfQBmiQrH5w6vbs2U87PTh1wrgVGPQE+8W+8lraTX69gzo5xm" +
                "Z7dhE/CGQrx7NJVHPEa5dhvJ65mcsoUgv+Q1y7PLRPWprSlHy77LlxcrmiX4qy6xTWb38CM7/3+AFQGqa35VtVnaoprq6/9xHa+Z" +
                "3cqRiti3qetIdyNMj/W4yRUmAEqv0Eeb0ISIPI5QbD8cZsffirEkEaRlNx2O1Yzyhggup9okFkpnzZ9XjsJ/sl+t7yzar8CRfZ2c" +
                "1rRFoQGR9LFIMslYHGnfiaC17S8j4LZQKwYMGDHgHkiBlKlXuaQOkIzm2XJ5kq/k28adjGkwknb8vqL15aDVaBl0Me+GefUKzAdF" +
                "oEdBWxRys9hWrIX5zAWWXhN4BGEIxuQmqLVrd5rWMiQJwFmXvqROTwiCHmITEdO9Md5vjp1xyezvywCv4Dz0s6C0TvJpUmqJjM+T" +
                "XL9Ss2+fX2WF8YyIynKU98K1+LHDPO9NhiShjRSPbN2XYnUV6D7uMcdStrWoF9f+PtItIEfY+29sG0EwAjC2Ns4DfE73GiKWee2/" +
                "7u4ZU9ZaLywLfdo9qmeBxYCwbsHKkIS0KgPHctG7IcBy9gOmUSs5rzF0ApP6fMiNLNJW3iplmoO6TR6YBV52dkTufcALNcBTUlyQ" +
                "p2Z/9dCodT6H1prrilJwuRC3+YBrTA/xNuliP4knPiaTSkWb0oG6cPjAKW29f+H3uAm111S61j4PGs+82JJu/WMR+4REZI08djuq" +
                "X+IEF7/soPjD2h3gPmOwf9sXBY/VN1F8j22VVMXLwaEBkfSxSDLJWBxp34mNtGDyqRdREjquXLlzA48WcDZiIae21NIiuE5gUKvZ" +
                "czbm04grHFU8j+3mghmZh+YRAi0XeVaiTxDzPHWoYKddErF/a1nxPXNcURl8n44wAYtQ+t3m4ioqo0ysB2neLpakEptoFVgox+nZ" +
                "/RkAgYgx7iWXzYdKeI1zfJvhMg6HpuCdfwdiPD5YttDGrbPPyQLwpspA5ZBnJMcTCwmazT/cNE5WdOiklFywNb7HnuIstHhWnXIF" +
                "F4xLsCsW3LgLkE10iah1AOaHsHRedhnZPZdVKJzlEQzldufMk05gKb2RnKj8i5H8I00PfaiPvlgIh7F2y8N86JMuiWZLxyiI89/I" +
                "QgrpDNF1QqMrlezPP9LlM3574KCNna3iSXYJTgZD6UaWItDQ9BNFxzgTngPemTUQ5nU7lG1c8+v6enAbcHnAEMB0x810NA4++YxT" +
                "t+2SPDIGgWiXJ71LCwJOUZqPziw/0GZQ75ZNfv+IEE6kK8nGQ4UH2E3jrIe8lwuf5YfytwxXnOzEtLmhAZH0sUgyyVgcad+IRzcV" +
                "zzEvAeC4hnhw4cO8NoSSy83+/W3DOJIVWszPg9cwePpxG+PLp6z9fTQ7V0Ge1BpobctQVR88JoI5MBnYuUf5Qdw9w4dK1KlkZ2G2" +
                "77JeJVzIjPG2R63frJtP5/AW9cGqWBD8R0CHcwAwkL8QdRJwTcijYVtw44KrIbuwVoVq+95yrjc3ALPUKPS2wiQ7+pSvhmpGDsET" +
                "PGDNZAz4L3ISkyWmK49EYjjs7D+PKyYTGHOjUeoFSmmkWk7O4wZSlUy1DRa8SLzw18UiUt2WyYbav1Zp7minamexUUNHaxR8jRR9" +
                "zW9wN3UPH++GMmor2APV9nAImTe+30a0WJAQoaN7DJml0w2bFuDUeytQl65tJk3eYArs6IZk2dytBocGk9Z1DijQbjHi4i9jAmoG" +
                "/YHbpIGqIhAWSYBZd1Vq+eeQUBlaTcWl3c1FDPXKjpPbsDO7gTsGHOSxsop0kksC6ZSLSSNJ/A9/iBBPJCsI94+gR0E5iqleeFw+" +
                "t/Xoc/AROaBco1KtoQGR9LFH6C7cqmlBjNNplq5EXRUgYUPHjx49IRY7/Dy+E+P+KB1wOPNUoYL2ikhzNrK1NNSJpI7tV2lZfNKd" +
                "Ckv+WOpzfRf1piKKBeRC1n2zXqycTa4S6w5jdyemXJnte0VJTqjCoJUWjnGsgoVwwwI67zbdXDu1C64W+pDh1kWC/lvhnIhmTEdu" +
                "MlSF1t6iNauOxBaQjvwJtzvxDFzdfKik9/AKcxbTH8ynQiyOcvfK2vk6pQ9hfj7+Mg3mg8LgTyTePiwWqoPk23Q0/I0N0ocylgKI" +
                "emdGAJrZM2nY9KXYb8r2sUtiyn5Cq44JTvKo8Yv9VKwEVNaXoZqN1BdFhU2inVdWsqkP9GoTir7ma0G4D4ZqiKnlzqM5DJpZzTvZ" +
                "jl+FbZcrrFVQ+Zyt/BpOp/kjtMxiX7T4e01/jhRSlYtuLUvogAup7vVehH3uEeCobSqqexxceImQ1AKpFgz03J8LkG4b22CSm27f" +
                "xA9E0X840smkX/ECCdg6q4mjjvlvx1uob38Xc7wGKLTPDLWrjTHUtaEBkfSxR+gu3KppQYrP9BIUxymy1s2XLly5fEXbGqhvoxrL" +
                "ucV6ChynP3qEvv2e7i5oSgn6GvLhYw/4QQMHyOptuqOOg1AU0CWv2rdfCBWOGud5qscFIakqWiLYz+nj2epuUTWo1OamFN4OWnJw" +
                "R5q/o+2sPA4Txzb6LhSFz7DeUO6YeTf8GhxtV0NW3kvt+fDofQlE3dWpArUY4qWf1/MWUOFzk1UCwM6iGHiqEvF4e/5uX+fKcvvu" +
                "lDHuESoW6LcMtsEfNA17BQzIVZJ2PyD2FNh4/lTHbZ2HVwKZf7IwuyslkMX86dBG9EvyTjSzpGmQEgchKlKdjiEwa8Arjff+sAq4" +
                "EfWD/AynZq4wml0TKVpyszYXQeLHxfnTYwyPSR2ypUClL9D2UGEuHkm4mUwHyfcGBCXlZNYJgoANMFkeygvKcAfWvn7VdbcbFc2i" +
                "Aw/O6Q4Jq/9z9AoET754CESBihdxHOXyGo+Y+bUh0wKUtCj+3Z/4gQTqAL01VqjpYkzjJHc/GHPEBmi1xA6JrkxADRmhAZH0sUmn" +
                "zMQBLXfLrZmRWveNy5k1yVKlSpdGviEb5I+k2/a53smjFHeIXsrV0Pgt1Bavv6+a+o1eS0ndDG+PvxOhf9M6Z7T0icufWbRhdt42" +
                "92hnj9bi9HB2T91hGHEdpAkOHyVpU6e6WNckfbiFW8JOofObNxDy1eyuMeXyjX1w/GLlrh6Pcq0dWMKlif8xn7EjCzMk9zLhJGw5" +
                "XbEEA0DL9YnTwzzv3+iOgx9m/ZL6RKfXNl28D7ZqBfkrt4Qc//gfGnRzKjQPiItOPpP+8W31YHb5Ekjw9GmYhkWwoNzMPg6OB8ML" +
                "OtNlCStGQaLXaYc1yDWI4PbG8yqyFkSrpK85qvFPbCvP0iq6EPI+1fx7B8ztibus/mP83foaBOKjMkOaK87CHaMIdxnz3Pp3+w4F" +
                "Yvg6xBCAlWqM0vuL015xzA19R+IL+9PkR+jHE77u3yC4O5KktoOCqSS8ef6zvNvQRevwcyHBC4+SON/kjYK2HPuP/IEE6SS+OI+2" +
                "f1pHY6Jm/xcR1/6ilvuQCapkXviLoQ==",
                "Ktz6BY7fDgt/47QP6+fCE8HsDRfr8XQZUvfjGt/8Sht7AqMaCgj4GHcNVxapEtoSixekDgYc3gkHILcEfCNi/1YmEvqKKP30DSpT" +
                "8NkqROzrKvfoQiqM5uAoG+XLJrLkDyRV5bYg/+bNHJ/pZxga7ZcTT/FxDhX2DQk8+4EDkwDl/eMFVPj6CuPypQ+s7bYTxOgGF0Lk" +
                "cxk44OcaudxSG9TZsBqV1wgZBtZrFi7V8RIQ1b0OrtX3CQTX0AQM2Xr/vtso+g7fEPXt4mPwS+dQ7BXs/ug18Y7mlfYZ5R/8quS5" +
                "AUnlSwfu5r4Miun5EQLt5hY08W8b+fWAHx/7CSN1APglxwVCKOAK3SmPD8IqpBPsKvgWWyprGRMp5RoXJ1cbcSS7GiwhGhlXHYEW" +
                "AxkME0IU2w4nDxkKzAn0BEYEnf+t/kz6G/kx9abzgfBo7mnsdukR6ebkm+bN4B3lO92p5EDaQOXq197mQ9Z06VHV5uwZ1RTxnNXV" +
                "9dfW+frF2E8AXtuiBZfevQph4nAPq+aKE2Tr5BZ38F4ZzfXeGk/7VhvlAMIaeQYnGe8LlRYyESUTKhb5DsEaOQrkHhYFgCLA/4Yl" +
                "bfrpJ1D1nimc8J0qgOzkKiTpcCqn5kIpJOVhJ6nk1SQ65akh0ubqHWPpqBnQ7PcU+/DrD7n1mQrd+hoFMgCE/4YF8PmkCnf0WQ8v" +
                "73gTMerXFpLlVRln4dsawN1ZG67ayxo/2DUZfdanFnDVOxMc1REPhNVUCqTWMQV32Nv/+NqH+hneZ/XO4bHwBuaR7K/qL+m1767m" +
                "A/Ul5YD6peQVADHlqQXE5iQLUOlwELrsdBXi8BkanvVOHsD6/yEWABslawWWJ4oKZClDD34qZRPfKskWhipNGXMp2BqsJ1wbOCXT" +
                "GiIiQxl4HroWSRpRE6cVLA+nEHEKXwtRBeUF+/9SAKb6vvqG9UD1zPDx76fs6OpB6Tvmuub+4SzlRd6k5B7bKuWX2LjmvNY+6ZXV" +
                "o+wn1cbwctV/9XfWoPox2PT/mdpKBaLdbApB4ScPZ+VNEwDqthb47j8ZPPTRGrH5WxtC/9ga1wROGVYKyxaoD2gTthRHD2sZjwqw" +
                "HXAFdCEbAKYkxfo5J6P1ISnn8FYqv+zUKlTplirH5p8pM+XzJ6bkmSUl5ZsireYHHy3p7BqN7FsWrfBpEWT1KwyC+rgG1v8pASwF" +
                "lPtQChH2Dg+78DgTqOulFuzmNBmd4ssaz95bG5Hb3hrx2FoZ/NbcFrnVfRMu1V8PXdWqCkbWjAXm1zgAM9rh+iTdvfWu4P3wwOTS" +
                "7EnpYuk07tHmbfM35dz4peRs/h/lAQSh5oYJHenhDnns+hOV8L0YSfUVHWb67SC6/zckEAXiJjUK5Sj1DjUqIhPOKpQWrCooGdAp" +
                "xBo/KFob/iXiGhgjYhmZH+oWkRuQExEXdg8tEsUK+QypBY0HVgAAAv/6avzb9eX2GvGH8evsZ+x36Z7n4eY+40LlW9+q5AfcH+VP" +
                "2ZvmQNcR6eLVaOw81YDwT9Uw9RzWSvqg15v/09nxBK3cFgog4NgOHuQIE5XofRZy7RUZoPK2Ggf4URuR/eAaJgNmGa0I8xYPDp4T" +
                "NBOKDwQY3ApsHMMFWSBzALkjHft9Jvn1mig28QYqBe27Ko/ptyr05vcpUOWDKLLkXSYg5ZEjl+YpIAfpNhxY7MgXa/DyEhf1yg0v" +
                "+mcIfv/eAtQESv36CcD3vQ5a8vESMe1pFljoBhnn464a8N9PG4Tc4xqz2XAZitcCFw/WshNL1aEPQdX3CvHV4AVZ15AAcdk6+zDc" +
                "E/aN307xduMZ7dvnnump7P7mzPFU5S33seSz/BnlSQKK5tUH9eg+DULsbhJS8E4X+/TJGxH6yh9h/0EjuAQfJuAJVyimDuAp3RKy" +
                "KloWyir8GCgqqRrOKE8bwybpGhAkexm/IBMX4BzHE4MYug+7ExILng79BUAJrgC7A1j7Jf4v9pf4Z/Ep8y/t9O2v6QzpC+eJ5Fzl" +
                "feCz5PvcFuUQ2oHmy9fo6DXWMOxU1TzwLdXi9MHV9fkN10T/C9maBLLbwgn33osOzeLFEiHnRhbh6+wY+vCeGlP2ShvX++oabgGB" +
                "Gf0GHhdvDNkTqhHQD5kWKwsmGxkGPB/MAMoidvvBJU32FCiE8bgpSe2nKsXp3Coc51YqZuUZKbfkKCcU5Y0keeZTIdroiB0d7DwZ" +
                "JPCCFMf0bw/X+RkKJf+XBHsEAP+lCW/5cQ75864St+40FsHp4Rgs5ZgaC+FLG3Hd8Rpt2o4ZDdgwF1vW7xNe1eoPG9VIC5LVOAbC" +
                "1uoAptiT+zTbaPZj3pvxJeJb7Wnm0+kc6yTnKfBq5Xz1teT9+gvllABr5ikGxuiiCwbs6RAK8OcVq/SFGrv5sB4I/1YiYARlJYwJ" +
                "0ydaDpMpmxKeKiYW8CrWGIYqkhpkKUobjSf2GgolmBnlIUAXLh4CFPMZABBGFWELPRBSBu0KBQFuBa771/+C9kD6s/HC9HDtdu/l" +
                "6XHqMufK5XPlleG45OTdCuXI2mbmTti76IHW9uto1ffvCtWU9GXVovl71u3+"));
        return fixtures;
    }

    private static final class Fixture {
        final int bitrate;
        final String encoded;
        final String expected;

        Fixture(int bitrate, String encoded, String expected) {
            this.bitrate = bitrate;
            this.encoded = encoded;
            this.expected = expected;
        }
    }
}
