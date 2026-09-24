package com.github.xingjianluo.openbankingapigatewayjava.downstream;

/** 模拟下游银行服务异常，对外不得透出细节。 */
public class DownstreamException extends RuntimeException {
    public DownstreamException(String message) {
        super(message);
    }
}
