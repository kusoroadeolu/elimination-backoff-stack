package io.github.kusoroadeolu.ebs;

import static io.github.kusoroadeolu.ebs.AdaptiveBackoffPolicy.*;

public interface WaitPolicy
{
    void increaseWait();

    void decreaseWait();

    void idle();

    static WaitPolicy adaptive(){
        return new AdaptiveWaitPolicy();
    }
}
