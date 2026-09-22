package cn.observe.starter.store;

import cn.observe.core.model.ObserveEvent;
import cn.observe.starter.ObserveProperties;
import cn.observe.starter.context.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件队列 + 后台批量落库。
 *
 * <p>★ 三条不可动摇的性质：
 * <ol>
 *   <li><b>不阻塞业务</b>：{@link #offer} 用非阻塞入队，队列满直接丢弃并计数。宁丢观测数据，不慢业务。</li>
 *   <li><b>不抛异常</b>：任何写入失败只记日志。</li>
 *   <li><b>不递归</b>：worker 线程写库前置 {@link TraceContext#setSuppress}，采集器据此跳过自身写入。</li>
 * </ol>
 */
public class EventQueue implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EventQueue.class);
    private static final String WORKER_NAME = "observe-writer";

    private final BlockingQueue<ObserveEvent> queue;
    private final EventWriter writer;
    private final ObserveProperties props;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong writeErrors = new AtomicLong();

    private volatile boolean running = true;
    private volatile Thread worker;

    public EventQueue(EventWriter writer, ObserveProperties props) {
        this.writer = writer;
        this.props = props;
        this.queue = new ArrayBlockingQueue<ObserveEvent>(Math.max(100, props.getQueueCapacity()));
    }

    @PostConstruct
    public void start() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, WORKER_NAME);
        worker.setDaemon(true);
        worker.start();
        log.info("[observe] 事件队列已启动: capacity={}, batchSize={}", props.getQueueCapacity(), props.getBatchSize());
    }

    /** 非阻塞入队。满了丢最新的，绝不阻塞调用线程。 */
    public void offer(ObserveEvent event) {
        if (event == null) {
            return;
        }
        received.incrementAndGet();
        if (!queue.offer(event)) {
            long n = dropped.incrementAndGet();
            if (n % 500 == 1) {
                log.warn("[observe] 事件队列已满，累计丢弃 {} 条（采集不拖垮业务是设计取舍）", n);
            }
        }
    }

    private void loop() {
        while (running || !queue.isEmpty()) {
            try {
                ObserveEvent first = queue.poll(props.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<ObserveEvent> batch = new ArrayList<ObserveEvent>(props.getBatchSize());
                batch.add(first);
                queue.drainTo(batch, props.getBatchSize() - 1);
                flush(batch);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // 兜底：worker 线程绝不能死，否则采集静默失效
                writeErrors.incrementAndGet();
                log.warn("[observe] 落库循环异常（已忽略，线程继续）: {}", t.toString());
            }
        }
    }

    private void flush(List<ObserveEvent> batch) {
        // ★ 抑制标记：让 DataSource/Redis 采集器跳过采集器自身的写入
        TraceContext.setSuppress(true);
        try {
            writer.write(batch);
            written.addAndGet(batch.size());
            if (props.isLogEvents() && log.isInfoEnabled()) {
                for (ObserveEvent e : batch) {
                    log.info("[observe] {}", e);
                }
            }
        } catch (Throwable t) {
            writeErrors.incrementAndGet();
            log.warn("[observe] 事件批量落库失败({} 条，已丢弃): {}", batch.size(), t.toString());
        } finally {
            TraceContext.setSuppress(false);
        }
    }

    public long getReceived() { return received.get(); }
    public long getDropped()  { return dropped.get(); }
    public long getWritten()  { return written.get(); }
    public long getWriteErrors() { return writeErrors.get(); }
    public int  getPending()  { return queue.size(); }

    @Override
    public void destroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        // 收尾：把残留事件尽量写掉
        List<ObserveEvent> rest = new ArrayList<ObserveEvent>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            flush(rest);
        }
        log.info("[observe] 已停止: 接收={} 落库={} 丢弃={} 失败={}",
                received.get(), written.get(), dropped.get(), writeErrors.get());
    }
}
