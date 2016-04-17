package com.mediator.lyngby.copenhacks;

/**
 * Created by fires on 17-04-2016.
 */
public class moodContainer {

    double sentimentalScore;
    double averageBetaWave;
    String mood;

    public double getSentimentalScore() {
        return sentimentalScore;
    }

    public double getAverageBetaWave() {
        return averageBetaWave;
    }

    public String getMood() {
        return mood;
    }

    public void setSentimentalScore(double sentimentalScore) {
        this.sentimentalScore = sentimentalScore;
    }

    public void setAverageBetaWave(double averageBetaWave) {
        this.averageBetaWave = averageBetaWave;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

}
