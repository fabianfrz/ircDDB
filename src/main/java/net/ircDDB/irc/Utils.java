package net.ircDDB.irc;

import java.util.Random;

public class Utils {

    public static int[] getShuffledAddresses(int num) {
        int[] shuffle = new int[num];
        for (int i = 0; i < num; i++) {
            shuffle[i] = i;
        }

        Random r = new Random();
        for (int i = 0; i < (num - 1); i++) {
            if (r.nextBoolean()) {
                int tmp;
                tmp = shuffle[i];
                shuffle[i] = shuffle[i + 1];
                shuffle[i + 1] = tmp;
            }
        }

        for (int i = (num - 1); i > 0; i--) {
            if (r.nextBoolean()) {
                int tmp;
                tmp = shuffle[i];
                shuffle[i] = shuffle[i - 1];
                shuffle[i - 1] = tmp;
            }
        }
        return shuffle;
    }
}
